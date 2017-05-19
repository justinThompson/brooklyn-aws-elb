package brooklyn.entity.proxy.aws;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.entity.EntitySpec;
import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.api.location.LocationSpec;
import org.apache.brooklyn.core.entity.Attributes;
import org.apache.brooklyn.core.entity.Entities;
import org.apache.brooklyn.core.entity.EntityAsserts;
import org.apache.brooklyn.core.entity.lifecycle.Lifecycle;
import org.apache.brooklyn.core.internal.BrooklynProperties;
import org.apache.brooklyn.core.location.Locations;
import org.apache.brooklyn.core.location.PortRanges;
import org.apache.brooklyn.core.mgmt.internal.LocalManagementContext;
import org.apache.brooklyn.core.test.BrooklynAppLiveTestSupport;
import org.apache.brooklyn.entity.group.DynamicCluster;
import org.apache.brooklyn.entity.software.base.SoftwareProcess;
import org.apache.brooklyn.entity.webapp.tomcat.Tomcat8Server;
import org.apache.brooklyn.location.jclouds.JcloudsSshMachineLocation;
import org.apache.brooklyn.location.ssh.SshMachineLocation;
import org.apache.brooklyn.test.Asserts;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.http.HttpAsserts;
import org.apache.brooklyn.util.net.Networking;
import org.apache.brooklyn.util.repeat.Repeater;
import org.apache.brooklyn.util.ssh.BashCommands;
import org.apache.brooklyn.util.text.Identifiers;
import org.apache.brooklyn.util.time.Duration;
import org.jclouds.elb.ELBApi;
import org.jclouds.elb.domain.HealthCheck;
import org.jclouds.elb.domain.LoadBalancer;
import org.jclouds.loadbalancer.LoadBalancerServiceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.AttachInternetGatewayRequest;
import com.amazonaws.services.ec2.model.CreateInternetGatewayRequest;
import com.amazonaws.services.ec2.model.CreateSubnetRequest;
import com.amazonaws.services.ec2.model.CreateVpcRequest;
import com.amazonaws.services.ec2.model.DeleteInternetGatewayRequest;
import com.amazonaws.services.ec2.model.DeleteSubnetRequest;
import com.amazonaws.services.ec2.model.DeleteVpcRequest;
import com.amazonaws.services.ec2.model.DetachInternetGatewayRequest;
import com.amazonaws.services.ec2.model.InternetGateway;
import com.amazonaws.services.ec2.model.Subnet;
import com.amazonaws.services.ec2.model.Vpc;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

public class ElbControllerLiveTest extends BrooklynAppLiveTestSupport {


    // TODO Add more tests
    // - other protocols, such as HTTPS, and TCP using `nc`
    // - removing nodes cleanly, to ensure they are removed from the pool
    // - health checker, to ensure

    // Note we need to pass an explicit list of AZs - if it tries to use all of them, then get an error
    // that us-east-1a and us-east-1d are not compatible.

    private static final Logger LOG = LoggerFactory.getLogger(ElbControllerLiveTest.class);

    public static final String PROVIDER = "aws-ec2";
    public static final String REGION_NAME = "us-east-1";
    public static final List<String> AVAILABILITY_ZONES = ImmutableList.of("us-east-1b", "us-east-1a");
    public static final String LOCATION_SPEC = PROVIDER + (REGION_NAME == null ? "" : ":" + REGION_NAME);
    public static final String LOCATION_AZ_SPEC = PROVIDER + ":" + AVAILABILITY_ZONES.get(0);
    public static final String SMALL_HARDWARE_ID = "m1.small";
    public static final int TCP_PORT = 1234;

    private static final URI warUri = URI.create("https://repo1.maven.org/maven2/org/apache/brooklyn/example/brooklyn-example-hello-world-webapp/0.7.0-incubating/brooklyn-example-hello-world-webapp-0.7.0-incubating.war");
    public static final String HEALTH_CHECK_TARGET_HTTP = "HTTP:81/";
    public static final String HEALTH_CHECK_TARGET_TCP = "TCP:81";
    public static final int HEALTH_CHECK_UNHEALTH_THRESHOLD = 10;
    public static final int HEALTH_CHECK_HEALTH_THRESHOLD = 10;
    public static final int HEALTH_CHECK_INTERVAL = 10;
    public static final int HEALTH_CHECK_TIMEOUT = 3;
    private final String TCP_PROTOCOL = "TCP";
    private final String CIDR_BLOCK = "10.0.0.0/16";

    // Image: {id=us-east-1/ami-7d7bfc14, providerId=ami-7d7bfc14, name=RightImage_CentOS_6.3_x64_v5.8.8.5, location={scope=REGION, id=us-east-1, description=us-east-1, parent=aws-ec2, iso3166Codes=[US-VA]}, os={family=centos, arch=paravirtual, version=6.0, description=rightscale-us-east/RightImage_CentOS_6.3_x64_v5.8.8.5.manifest.xml, is64Bit=true}, description=rightscale-us-east/RightImage_CentOS_6.3_x64_v5.8.8.5.manifest.xml, version=5.8.8.5, status=AVAILABLE[available], loginUser=root, userMetadata={owner=411009282317, rootDeviceType=instance-store, virtualizationType=paravirtual, hypervisor=xen}}
    //runTest(ImmutableMap.of("imageId", "us-east-1/ami-7d7bfc14", "hardwareId", SMALL_HARDWARE_ID));

    protected BrooklynProperties brooklynProperties;
    String identity;
    String credential;

    protected Location loc;
    protected Location locAZ;
    protected List<Location> locs;

    private ElbController elb;

    private SshMachineLocation localMachine;
    private AmazonEC2Client client;
    Subnet subnet;
    Vpc vpc;
    InternetGateway internetGateway;

    @BeforeMethod(alwaysRun=true)
    @Override
    public void setUp() throws Exception {
        // Don't let any defaults from brooklyn.properties (except credentials) interfere with test
        brooklynProperties = BrooklynProperties.Factory.newDefault();
        brooklynProperties.remove("brooklyn.jclouds."+PROVIDER+".image-description-regex");
        brooklynProperties.remove("brooklyn.jclouds."+PROVIDER+".image-name-regex");
        brooklynProperties.remove("brooklyn.jclouds."+PROVIDER+".image-id");
        brooklynProperties.remove("brooklyn.jclouds."+PROVIDER+".inboundPorts");
        brooklynProperties.remove("brooklyn.jclouds."+PROVIDER+".hardware-id");

        // Also removes scriptHeader (e.g. if doing `. ~/.bashrc` and `. ~/.profile`, then that can cause "stdin: is not a tty")
        brooklynProperties.remove("brooklyn.ssh.config.scriptHeader");

        mgmt = new LocalManagementContext(brooklynProperties);

        super.setUp();
        identity = brooklynProperties.getFirst("brooklyn.location.jclouds.aws-ec2.identity");
        credential = brooklynProperties.getFirst("brooklyn.location.jclouds.aws-ec2.credential");

        Map<String,?> flags = ImmutableMap.of("tags", ImmutableList.of(getClass().getName()));
        loc = mgmt.getLocationRegistry().resolve(LOCATION_SPEC, flags);
        locAZ = mgmt.getLocationRegistry().resolve(LOCATION_AZ_SPEC, flags);
        locs = ImmutableList.of(loc);
        localMachine = mgmt.getLocationManager().createLocation(LocationSpec.create(SshMachineLocation.class)
                .configure("address", Networking.getLocalHost()));
    }

    @AfterMethod(alwaysRun=true)
    @Override
    public void tearDown() throws Exception {
        try {
            if (elb != null) {
                elb.deleteLoadBalancer();
            }
            if (app != null) {
                app.stop();
            }
        } catch (Exception e) {
            LOG.error("error deleting/stopping ELB app; continuing with shutdown...", e);
        } finally {
            super.tearDown();
        }
    }

    @Test(groups="Live")
    public void testCreateLoadBalancer() throws Exception {
        elb = app.createAndManageChild(
            EntitySpec.create(ElbController.class)
                .configure(ElbController.LOAD_BALANCER_NAME, "myname-"+System.getProperty("user.name")+"-"+Identifiers.makeRandomId(8))
                .configure(ElbController.AVAILABILITY_ZONES, AVAILABILITY_ZONES)
                .configure(ElbController.INSTANCE_PORT, 8080)
        );
        
        app.addLocations(locs);
        app.start(ImmutableList.<Location>of());

        checkNotNull(elb.getAttribute(ElbController.HOSTNAME));
        EntityAsserts.assertAttributeEqualsEventually(elb, Attributes.SERVICE_UP, true);
        EntityAsserts.assertAttributeEqualsEventually(elb, Attributes.SERVICE_STATE_ACTUAL, Lifecycle.RUNNING);
        EntityAsserts.assertAttributeEventually(elb, ElbController.LOAD_BALANCER_SUBNETS, Predicates.<Collection<String>>notNull());
        EntityAsserts.assertAttributeEventually(elb, ElbController.LOAD_BALANCER_SECURITY_GROUPS, Predicates.notNull());
        EntityAsserts.assertAttributeEventually(elb, ElbController.VPC_ID, Predicates.notNull());
        EntityAsserts.assertAttributeEventually(elb, ElbController.CANONICAL_HOSTED_ZONE_ID, Predicates.notNull());
        EntityAsserts.assertAttributeEventually(elb, ElbController.CANONICAL_HOSTED_ZONE_NAME, Predicates.notNull());

        Entities.dumpInfo(elb);
    }



    @Test(groups="Live")
    public void testRebindLoadBalancer() throws Exception {
        String elbName = generateElbName("rebind");
        elb = app.createAndManageChild(EntitySpec.create(ElbController.class)
                .configure(ElbController.LOAD_BALANCER_NAME, elbName)
                .configure(ElbController.AVAILABILITY_ZONES, AVAILABILITY_ZONES)
                .configure(ElbController.INSTANCE_PORT, 8080));

        app.start(locs);
        String origHostname = elb.getAttribute(ElbController.HOSTNAME);

        EntityAsserts.assertAttributeEqualsEventually(elb, Attributes.SERVICE_STATE_ACTUAL, Lifecycle.RUNNING);
        EntityAsserts.assertAttributeEventually(elb, ElbController.LOAD_BALANCER_SUBNETS, Predicates.<Collection<String>>notNull());
        EntityAsserts.assertAttributeEventually(elb, ElbController.LOAD_BALANCER_SECURITY_GROUPS, Predicates.notNull());

        // With no interesting differences in configuration
        ElbController elb2 = app.createAndManageChild(EntitySpec.create(ElbController.class)
                .configure(ElbController.LOAD_BALANCER_NAME, elbName)
                .configure(ElbController.AVAILABILITY_ZONES, AVAILABILITY_ZONES)
                .configure(ElbController.LOAD_BALANCER_SECURITY_GROUPS, elb.sensors().get(ElbController.LOAD_BALANCER_SECURITY_GROUPS))
                .configure(ElbController.LOAD_BALANCER_SUBNETS, elb.sensors().get(ElbController.LOAD_BALANCER_SUBNETS))
                .configure(ElbController.BIND_TO_EXISTING, true));
        elb2.start(locs);
        assertEquals(elb2.getAttribute(ElbController.HOSTNAME), origHostname);

        // With lots of differences
        // TODO test scheme, subnets and security groups
        ElbController elb3 = app.createAndManageChild(EntitySpec.create(ElbController.class)
                .configure(ElbController.LOAD_BALANCER_NAME, elbName)
                .configure(ElbController.AVAILABILITY_ZONES, AVAILABILITY_ZONES)
                .configure(ElbController.BIND_TO_EXISTING, true)
                .configure(ElbController.HEALTH_CHECK_TARGET, HEALTH_CHECK_TARGET_HTTP)
                .configure(ElbController.INSTANCE_PORT, 1234)
                .configure(ElbController.LOAD_BALANCER_SECURITY_GROUPS, elb.sensors().get(ElbController.LOAD_BALANCER_SECURITY_GROUPS))
                .configure(ElbController.LOAD_BALANCER_SUBNETS, elb.sensors().get(ElbController.LOAD_BALANCER_SUBNETS))
                .configure(ElbController.INSTANCE_PROTOCOL, TCP_PROTOCOL)
                .configure(ElbController.LOAD_BALANCER_PORT, 1235)
                .configure(ElbController.LOAD_BALANCER_PROTOCOL, TCP_PROTOCOL));

        elb3.start(locs);
        assertEquals(elb3.getAttribute(ElbController.HOSTNAME), origHostname);
    }


    private String generateElbName(String prefix) {
        String elbName = String.format("%s-%s-%s", prefix, System.getProperty("user.name"), Identifiers.makeRandomId(8));
        return elbName.length() > 32 ? elbName.substring(0, 31) : elbName;
    }

    @Test(groups="Live")
    public void testReplaceExistingLoadBalancerForbiddenByDefault() throws Exception {
        String elbName = generateElbName("replaceForbidden");
        elb = app.createAndManageChild(EntitySpec.create(ElbController.class)
                .configure(ElbController.LOAD_BALANCER_NAME, elbName)
                .configure(ElbController.AVAILABILITY_ZONES, AVAILABILITY_ZONES));
        app.start(locs);

        // Cannot replace an existing load balancer unless EXPLICITLY configured to do so
        ElbController elb2 = app.createAndManageChild(EntitySpec.create(ElbController.class)
                .configure(ElbController.LOAD_BALANCER_NAME, elbName)
                .configure(ElbController.AVAILABILITY_ZONES, AVAILABILITY_ZONES));
        try {
            elb2.start(locs);
        } catch (Exception e) {
            IllegalStateException unwrapped = Exceptions.getFirstThrowableOfType(e, IllegalStateException.class);
            if (unwrapped == null || !unwrapped.toString().contains("because already exists")) throw e;
        }
    }

    @Test(groups="Live")
    public void testReplaceExistingLoadBalancer() throws Exception {
        String elbName = generateElbName("replaceExistingLB");
        elb = app.createAndManageChild(EntitySpec.create(ElbController.class)
                .configure(ElbController.LOAD_BALANCER_NAME, elbName)
                .configure(ElbController.AVAILABILITY_ZONES, AVAILABILITY_ZONES)
                .configure(ElbController.INSTANCE_PORT, 8080));
        app.start(locs);
        String origHostname = elb.getAttribute(ElbController.HOSTNAME);

        ElbController elb2 = app.createAndManageChild(EntitySpec.create(ElbController.class)
                .configure(ElbController.LOAD_BALANCER_NAME, elbName)
                .configure(ElbController.AVAILABILITY_ZONES, AVAILABILITY_ZONES)
                .configure(ElbController.REPLACE_EXISTING, true));
        elb2.start(locs);

        // TODO Not the best assertion; AWS are allowed to reuse hostnames, but unlikely we'll get exactly the same one back!
        assertNotEquals(elb2.getAttribute(ElbController.HOSTNAME), origHostname);
    }

    @Test(groups="Live")
    public void testLoadBalancerWithHttpTargets() throws Exception {

        DynamicCluster cluster = app.createAndManageChild(EntitySpec.create(DynamicCluster.class)
                .configure(DynamicCluster.INITIAL_SIZE, 1)
                .configure(DynamicCluster.MEMBER_SPEC, EntitySpec.create(Tomcat8Server.class)
                        .configure("war", warUri.toString())
                        .configure(Tomcat8Server.HTTP_PORT, PortRanges.fromInteger(8080))));


        elb = app.createAndManageChild(EntitySpec.create(ElbController.class)
                .configure(ElbController.AVAILABILITY_ZONES, AVAILABILITY_ZONES)
                .configure(ElbController.SERVER_POOL, cluster)
                .configure(ElbController.LOAD_BALANCER_NAME, generateElbName("httpTargets"))
                .configure(ElbController.LOAD_BALANCER_PORT, 80)
                .configure(ElbController.INSTANCE_PORT, 8080));

        app.start(ImmutableList.<Location>of(locAZ));

        Tomcat8Server appserver = (Tomcat8Server) Iterables.getOnlyElement(cluster.getMembers());
        JcloudsSshMachineLocation machine = (JcloudsSshMachineLocation) Locations.findUniqueMachineLocation(appserver.getLocations()).get();

        // double-check that app-server really is reachable (so don't complain about ELB if it's not ELB's fault!)
        String directurl = appserver.getAttribute(Tomcat8Server.ROOT_URL);
        HttpAsserts.assertHttpStatusCodeEventuallyEquals(directurl, 200);
        HttpAsserts.assertContentContainsText(directurl, "Hello");

        Asserts.succeedsEventually(ImmutableMap.of("timeout", 5*60*1000), new Runnable() {
                @Override public void run() {
                    String url = "http://"+elb.getAttribute(ElbController.HOSTNAME)+":80/";
                    HttpAsserts.assertHttpStatusCodeEventuallyEquals(url, 200);
                    HttpAsserts.assertContentContainsText(url, "Hello");
                }});

        assertEquals(elb.getAttribute(ElbController.SERVER_POOL_TARGETS), ImmutableMap.of(appserver, machine.getNode().getProviderId()));

        cluster.resize(0);
        Asserts.succeedsEventually(new Runnable() {
            @Override public void run() {
                assertEquals(elb.getAttribute(ElbController.SERVER_POOL_TARGETS), ImmutableMap.of());
            }});
    }

    // A very fast check that the configuration is all accepted, without error
    @Test(groups={"Live", "Live-sanity"})
    public void testLoadBalancerWithTcpTargetsAndEmptyCluster() throws Exception {
        DynamicCluster cluster = app.createAndManageChild(EntitySpec.create(DynamicCluster.class)
                .configure(DynamicCluster.AVAILABILITY_ZONE_NAMES, AVAILABILITY_ZONES)
                .configure(DynamicCluster.INITIAL_SIZE, 0));

        elb = app.createAndManageChild(EntitySpec.create(ElbController.class)
                .configure(ElbController.AVAILABILITY_ZONES, AVAILABILITY_ZONES)
                .configure(ElbController.SERVER_POOL, cluster)
                .configure(ElbController.LOAD_BALANCER_NAME, generateElbName("tcpTargetsAndEmptyCluster"))
                .configure(ElbController.LOAD_BALANCER_PROTOCOL, TCP_PROTOCOL)
                .configure(ElbController.INSTANCE_PROTOCOL, TCP_PROTOCOL)
                .configure(ElbController.LOAD_BALANCER_PORT, 1234)
                .configure(ElbController.INSTANCE_PORT, 1235)
                .configure(ElbController.HEALTH_CHECK_TARGET, HEALTH_CHECK_TARGET_HTTP));

        app.start(ImmutableList.<Location>of(locAZ));
        final String elbHostname = elb.getAttribute(ElbController.HOSTNAME);
        assertNotNull(elbHostname);
    }

    // TODO This is failing currently due to `nc` not being insalled correctly on the centos VM
    // Also, the `nc -l` and `echo "..." | nc hostname port` seem brittle when testing this manually,
    // for whether the command returns properly (hence the `-w 10`) and whether the output file gets
    // populated. Should we use stdout?
    @Test(groups={"Live", "WIP"})
    public void testLoadBalancerWithTcpTargets()  {
        final DynamicCluster cluster = app.createAndManageChild(EntitySpec.create(DynamicCluster.class)
                .configure(DynamicCluster.INITIAL_SIZE, 1)
                .configure(DynamicCluster.MEMBER_SPEC, EntitySpec.create(Tomcat8Server.class)
                        .configure("war", warUri.toString())
                        .configure(Tomcat8Server.HTTP_PORT, PortRanges.fromInteger(8080))));

        elb = app.createAndManageChild(EntitySpec.create(ElbController.class)
                .configure(ElbController.AVAILABILITY_ZONES, AVAILABILITY_ZONES)
                .configure(ElbController.SERVER_POOL, cluster)
                .configure(ElbController.LOAD_BALANCER_NAME, generateElbName("tcpTargets"))
                .configure(ElbController.LOAD_BALANCER_PROTOCOL, TCP_PROTOCOL)
                .configure(ElbController.INSTANCE_PROTOCOL, TCP_PROTOCOL)
                .configure(ElbController.LOAD_BALANCER_PORT, TCP_PORT)
                .configure(ElbController.INSTANCE_PORT, 1235)
                .configure(ElbController.HEALTH_CHECK_TARGET, HEALTH_CHECK_TARGET_TCP));


        app.start(ImmutableList.<Location>of(locAZ));
        final String elbHostname = elb.getAttribute(ElbController.HOSTNAME);

        SoftwareProcess appServer = (SoftwareProcess) Iterables.getOnlyElement(cluster.getMembers());
        JcloudsSshMachineLocation machine = (JcloudsSshMachineLocation) Locations.findUniqueMachineLocation(appServer.getLocations()).get();

        // Set up `nc` (and will you that to setup server-side port listener, and to connect to it)
        ImmutableList<String> installNcCommand = ImmutableList.of(BashCommands.installPackage(ImmutableMap.of("apt-get", "netcat", "yum", "nc.x86_64"), "netcat"));
        machine.execScript("install-nc", installNcCommand);
        machine.execScript("run-nc-listener", ImmutableList.of("nohup nc -l 1235 &"));


        LOG.warn("***********");
        // test the tcp connection directly
        Asserts.succeedsEventually(new Runnable() {
            @Override
            public void run() {
                assertTrue(cluster.getAttribute(DynamicCluster.CLUSTER_ONE_AND_ALL_MEMBERS_UP));
                Entity instance = (Entity) cluster.getChildren().toArray()[1];
                final String instanceAddress = instance.getAttribute(Attributes.ADDRESS);

                try {
                    final Socket tcpClient = new Socket(instanceAddress, 1235);
                    assertTrue(tcpClient.isConnected());
                } catch (IOException e) {
                    fail("could not directly connect to " + instanceAddress + " on port " + 1235);
                }
            }
        });

        Asserts.succeedsEventually(new Runnable() {
               @Override
               public void run() {

                   try {
                       final Socket tcpClient = new Socket(elbHostname, TCP_PORT);
                       assertTrue(tcpClient.isConnected());
                   } catch (IOException e) {
                       fail("could not connect to " + elbHostname + " on port " + TCP_PORT);
                   }
               }
        });

        assertEquals(elb.getAttribute(ElbController.SERVER_POOL_TARGETS), ImmutableMap.of(appServer, machine.getNode().getProviderId()));

    }
    @Test(groups="Live")
    public void testHealthCheck() throws Exception {
        String elbName = generateElbName("healthCheck");
        ElbControllerImpl elbImpl = (ElbControllerImpl) Entities.deproxy(app.createAndManageChild(EntitySpec.create(ElbController.class)
                .configure(ElbController.AVAILABILITY_ZONES, AVAILABILITY_ZONES)
                .configure(ElbController.LOAD_BALANCER_NAME, elbName)
                .configure(ElbController.LOAD_BALANCER_PROTOCOL, TCP_PROTOCOL)
                .configure(ElbController.INSTANCE_PROTOCOL, TCP_PROTOCOL)
                .configure(ElbController.LOAD_BALANCER_PORT, TCP_PORT)
                .configure(ElbController.INSTANCE_PORT, 1235)
                .configure(ElbController.HEALTH_CHECK_TARGET, HEALTH_CHECK_TARGET_HTTP)
                .configure(ElbController.HEALTH_CHECK_HEALTHY_THRESHOLD, HEALTH_CHECK_HEALTH_THRESHOLD)
                .configure(ElbController.HEALTH_CHECK_UNHEALTHY_THRESHOLD, HEALTH_CHECK_UNHEALTH_THRESHOLD)
                .configure(ElbController.HEALTH_CHECK_INTERVAL, HEALTH_CHECK_INTERVAL)
                .configure(ElbController.HEALTH_CHECK_TIMEOUT, HEALTH_CHECK_TIMEOUT)));

        elb = elbImpl;

        app.addLocations(locs);

        app.start(locs);
        final ELBApi elbApi = createElbApi(elbImpl);
        final LoadBalancer loadBalancer = elbApi.getLoadBalancerApi().get(elbName);
        final HealthCheck healthCheck = loadBalancer.getHealthCheck();
        assertEquals(loadBalancer.getName(), elbName);
        assertEquals(healthCheck.getTarget(), HEALTH_CHECK_TARGET_HTTP);
        assertEquals(healthCheck.getHealthyThreshold(), HEALTH_CHECK_UNHEALTH_THRESHOLD);
        assertEquals(healthCheck.getUnhealthyThreshold(), HEALTH_CHECK_UNHEALTH_THRESHOLD);
        assertEquals(healthCheck.getInterval(), HEALTH_CHECK_INTERVAL);
        assertEquals(healthCheck.getTimeout(), HEALTH_CHECK_TIMEOUT);

    }

    private ELBApi createElbApi(ElbControllerImpl elbImpl) {
        final LoadBalancerServiceContext loadBalancerServiceContext = elbImpl.newLoadBalancerServiceContext(identity, credential);
        return elbImpl.newELBApi(loadBalancerServiceContext);
    }

    @Test(groups="Live")
    public void testAvailabilityZones() throws Exception {
        String elbName = generateElbName("availZones");
        ElbControllerImpl elbImpl = (ElbControllerImpl) Entities.deproxy(app.createAndManageChild(EntitySpec.create(ElbController.class)
                .configure(ElbController.AVAILABILITY_ZONES, AVAILABILITY_ZONES)
                .configure(ElbController.LOAD_BALANCER_NAME, elbName)
                .configure(ElbController.LOAD_BALANCER_PROTOCOL, TCP_PROTOCOL)
                .configure(ElbController.INSTANCE_PROTOCOL, TCP_PROTOCOL)
                .configure(ElbController.LOAD_BALANCER_PORT, TCP_PORT)
                .configure(ElbController.INSTANCE_PORT, 1235)));

        final ELBApi elbApi = createElbApi(elbImpl);

        elb = elbImpl;

        app.addLocations(locs);

        app.start(locs);

        final LoadBalancer loadBalancer = elbApi.getLoadBalancerApi().get(elbName);
        assertEquals(loadBalancer.getName(), elbName);
        assertTrue(loadBalancer.getAvailabilityZones().containsAll(AVAILABILITY_ZONES));
    }

    @Test(groups={"Live", "WIP"})
    public void testSubnets() throws Exception {
        setupTestVPCAndSubnets();

        //TODO setup the subnets first
        String elbName = generateElbName("subnets");
        ElbControllerImpl elbImpl = (ElbControllerImpl) Entities.deproxy(app.createAndManageChild(EntitySpec.create(ElbController.class)
                .configure(ElbController.LOAD_BALANCER_SUBNETS, ImmutableSet.of(subnet.getSubnetId()))
                .configure(ElbController.LOAD_BALANCER_NAME, elbName)
                .configure(ElbController.LOAD_BALANCER_PROTOCOL, TCP_PROTOCOL)
                .configure(ElbController.INSTANCE_PROTOCOL, TCP_PROTOCOL)
                .configure(ElbController.LOAD_BALANCER_PORT, TCP_PORT)
                .configure(ElbController.INSTANCE_PORT, 1235)));

        final ELBApi elbApi = createElbApi(elbImpl);

        elb = elbImpl;

        app.addLocations(locs);

        app.start(locs);

        final LoadBalancer loadBalancer = elbApi.getLoadBalancerApi().get(elbName);
        assertEquals(loadBalancer.getName(), elbName);
        assertTrue(loadBalancer.getSubnets().contains(subnet.getSubnetId()));

        tearDownTestVPCandSubnets();

    }
    protected AmazonEC2Client newAmazonEc2Client() {
        AWSCredentials awsCredentials = new BasicAWSCredentials(identity, credential);
        AmazonEC2Client client = new AmazonEC2Client(awsCredentials);

        Region targetRegion = Region.getRegion(Regions.fromName(REGION_NAME));
        client.setRegion(targetRegion);

        return client;
    }
    protected void setupTestVPCAndSubnets(){
        client = newAmazonEc2Client();
        CreateVpcRequest testVpcRequest = new CreateVpcRequest()
                .withCidrBlock(CIDR_BLOCK);

        vpc = client.createVpc(testVpcRequest).getVpc();

        CreateSubnetRequest subnetRequest = new CreateSubnetRequest()
                .withVpcId(vpc.getVpcId())
                .withCidrBlock(CIDR_BLOCK);

        CreateInternetGatewayRequest createInternetGatewayRequest = new CreateInternetGatewayRequest();
        internetGateway = client.createInternetGateway(createInternetGatewayRequest).getInternetGateway();

        subnet = client.createSubnet(subnetRequest).getSubnet();

        AttachInternetGatewayRequest attachInternetGatewayRequest = new AttachInternetGatewayRequest()
                .withInternetGatewayId(internetGateway.getInternetGatewayId())
                .withVpcId(vpc.getVpcId());

        client.attachInternetGateway(attachInternetGatewayRequest);

        LOG.error("created VPC"+ vpc.getVpcId());
    }

    protected void tearDownTestVPCandSubnets(){
        final DeleteSubnetRequest deleteSubnetRequest = new DeleteSubnetRequest().withSubnetId(subnet.getSubnetId());
        final DetachInternetGatewayRequest detachInternetGatewayRequest = new DetachInternetGatewayRequest().withInternetGatewayId(internetGateway.getInternetGatewayId()).withVpcId(vpc.getVpcId());
        final DeleteInternetGatewayRequest deleteInternetGatewayRequest = new DeleteInternetGatewayRequest().withInternetGatewayId(internetGateway.getInternetGatewayId());
        final DeleteVpcRequest deleteVpcRequest = new DeleteVpcRequest().withVpcId(vpc.getVpcId());

        Repeater.create("tear down subnet, vpc and internet gateway")
                .until(new Callable<Boolean>() {
                    public Boolean call() {
                        client.deleteSubnet(deleteSubnetRequest);
                        client.deleteInternetGateway(deleteInternetGatewayRequest);
                        client.detachInternetGateway(detachInternetGatewayRequest);
                        client.deleteVpc(deleteVpcRequest);

                        return true;
                    }
                })
                .every(Duration.THIRTY_SECONDS)
                .limitIterationsTo(10)
                .run();
    }

}