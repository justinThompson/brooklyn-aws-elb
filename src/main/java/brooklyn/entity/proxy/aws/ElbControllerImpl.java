package brooklyn.entity.proxy.aws;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static org.jclouds.reflect.Reflection2.typeToken;

import java.util.Collection;
import java.util.Set;

import javax.annotation.Nullable;

import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.entity.Group;
import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.api.sensor.AttributeSensor;
import org.apache.brooklyn.config.ConfigKey;
import org.apache.brooklyn.config.ConfigKey.HasConfigKey;
import org.apache.brooklyn.core.entity.Attributes;
import org.apache.brooklyn.core.entity.lifecycle.Lifecycle;
import org.apache.brooklyn.core.entity.lifecycle.ServiceStateLogic;
import org.apache.brooklyn.core.entity.lifecycle.ServiceStateLogic.ServiceProblemsLogic;
import org.apache.brooklyn.core.feed.ConfigToAttributes;
import org.apache.brooklyn.core.location.Locations;
import org.apache.brooklyn.core.location.Machines;
import org.apache.brooklyn.entity.proxy.AbstractNonProvisionedControllerImpl;
import org.apache.brooklyn.location.jclouds.JcloudsLocation;
import org.apache.brooklyn.location.jclouds.JcloudsMachineNamer;
import org.apache.brooklyn.location.jclouds.JcloudsSshMachineLocation;
import org.apache.brooklyn.util.core.config.ConfigBag;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.guava.Maybe;
import org.apache.brooklyn.util.text.Strings;
import org.jclouds.ContextBuilder;
import org.jclouds.aws.ec2.AWSEC2Api;
import org.jclouds.ec2.domain.AvailabilityZoneInfo;
import org.jclouds.elb.ELBApi;
import org.jclouds.elb.domain.Listener;
import org.jclouds.elb.domain.LoadBalancer;
import org.jclouds.elb.domain.Protocol;
import org.jclouds.elb.domain.Scheme;
import org.jclouds.loadbalancer.LoadBalancerServiceContext;
import org.jclouds.util.Closeables2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;

public class ElbControllerImpl extends AbstractNonProvisionedControllerImpl implements ElbController {

    // TODO Add unit tests for rebind

    // TODO Use the SoftwareProcess's approach of expectedState and serviceUpIndicators
    // TODO What should lifecycle be if effector deleteLoadBalancer is called?

    private static final Logger LOG = LoggerFactory.getLogger(ElbControllerImpl.class);

    @Override
    protected void doStart(Collection<? extends Location> locations) {
        ServiceProblemsLogic.clearProblemsIndicator(this, START);
        ServiceStateLogic.setExpectedState(this, Lifecycle.STARTING);
        try {
            JcloudsLocation loc = inferLocation(locations);
            checkArgument("aws-ec2".equals(loc.getProvider()), "start must have exactly one jclouds location for aws-ec2, but given provider %s (%s)", loc.getProvider(), loc);
            sensors().set(JCLOUDS_LOCATION, loc);

            ConfigToAttributes.apply(this);

            startLoadBalancer();
            isActive = true;

            sensors().set(SERVICE_UP, true);

        } finally {
            ServiceStateLogic.setExpectedState(this, Lifecycle.RUNNING);
        }
    }

    @Override
    public void stop() {
        // TODO should we deleteLoadBalancer?
        String elbName = checkLoadBalancerName(LOAD_BALANCER_NAME);
        JcloudsLocation loc = getAttribute(JCLOUDS_LOCATION);
        if (elbName != null && loc != null && doesLoadBalancerExist(elbName)) {
            deleteLoadBalancer(elbName);
        }
        ServiceStateLogic.setExpectedState(this, Lifecycle.STOPPED);
        sensors().set(SERVICE_UP, false);
    }

    @Override
    public void reload() {
        try {
            JcloudsLocation loc = sensors().get(JCLOUDS_LOCATION);

            if (Boolean.FALSE.equals(sensors().get(SERVICE_UP))) {
                // TODO Guard this better, in case of concurrent calls?
                // TODO guard with lifecycle state, so will do this when starting?
                LOG.info("Not reloading ELB configuration, because ElbController is not running");
                return;
            }
            if (loc == null) {
                // TODO Guard this better, in case of concurrent calls?
                // TODO guard with lifecycle state, so will do this when starting?
                LOG.warn("No location for ELB "+this+", cannot reload");
                return;
            }
            String elbName = checkLoadBalancerName(LOAD_BALANCER_NAME);
            Set<String> targetAddresses = super.getServerPoolAddresses();

            LoadBalancerServiceContext loadBalancerServiceContext = null;
            try {
                loadBalancerServiceContext = newLoadBalancerServiceContext(loc.getIdentity(), loc.getCredential());
                ELBApi api = api(loadBalancerServiceContext);

                Set<String> instances = ImmutableSet.copyOf(targetAddresses);
                LoadBalancer loadBalancer = api.getLoadBalancerApi().get(elbName);
                Set<String> oldInstances = ImmutableSet.copyOf(loadBalancer.getInstanceIds());
                Set<String> removedInstances = Sets.difference(oldInstances, instances);
                Set<String> addedInstances = Sets.difference(instances, oldInstances);

                if (!addedInstances.isEmpty()) {
                    api.getInstanceApi().registerInstancesWithLoadBalancer(addedInstances, elbName);
                }
                if (!removedInstances.isEmpty()) {
                    api.getInstanceApi().registerInstancesWithLoadBalancer(removedInstances, elbName);
                }
            } finally {
                Closeables2.closeQuietly(loadBalancerServiceContext.unwrap());
            }
        } catch (RuntimeException e) {
            LOG.warn("Problem reloading", e);
            throw Exceptions.propagate(e);
        }
    }

    private String checkLoadBalancerName(AttributeSensor<String> attributeSensor) {
        String elbName = getAttribute(attributeSensor);
        checkArgument(elbName.length() <= 32, "elbName is too long, must be less than 32 chars: " + elbName);
        return elbName;
    }

    protected void startLoadBalancer() {
        String elbName = checkLoadBalancerName(LOAD_BALANCER_NAME);

        if (getRequiredConfig(BIND_TO_EXISTING)) {
            checkNotNull(elbName, "load balancer name must not be null if binding to existing");
            checkArgument(Strings.isNonBlank(elbName), "load balancer name must be non-blank if binding to existing");
            reinitLoadBalancer();
        } else if (getRequiredConfig(REPLACE_EXISTING)) {
            checkNotNull(elbName, "load balancer name must not be null if configured to replace any existing");
            checkArgument(Strings.isNonBlank(elbName), "load balancer name must be non-blank if configured to replace any existing");
            if (doesLoadBalancerExist(elbName)) {
                deleteLoadBalancer(elbName);
            }
            createLoadBalancer(elbName);
        } else {
            if (Strings.isBlank(elbName)) {
                elbName = generateUnusedElbName();
                sensors().set(LOAD_BALANCER_NAME, elbName);
            } else {
                if (doesLoadBalancerExist(elbName)) {
                    throw new IllegalStateException("Cannot create ELB "+elbName+" in "+this+", because already exists (consider using configuration "+REPLACE_EXISTING.getName()+")");
                }
            }

            createLoadBalancer(elbName);
        }
    }

    protected void createLoadBalancer(String elbName) {
        final JcloudsLocation loc = getLocation();

        int loadBalancerPort = getRequiredConfig(LOAD_BALANCER_PORT);
        int instancePort = getRequiredConfig(INSTANCE_PORT);
        String instanceProtocol = getRequiredConfig(INSTANCE_PROTOCOL);
        String elbScheme = getConfig(LOAD_BALANCER_SCHEME);
        String loadBalancerProtocol = getRequiredConfig(LOAD_BALANCER_PROTOCOL);
        Collection<String> securityGroups = getConfig(LOAD_BALANCER_SECURITY_GROUPS);
        Collection<String> subnets = getConfig(LOAD_BALANCER_SUBNETS);
        String sslCertificateId = getConfig(SSL_CERTIFICATE_ID);
        Set<String> availabilityZoneNames = getAvailabilityZones(loc);
        Boolean healthCheckEnabled = getConfig(HEALTH_CHECK_ENABLED);
        Integer healthCheckInterval = getConfig(HEALTH_CHECK_INTERVAL);
        Integer healthCheckTimeout = getConfig(HEALTH_CHECK_TIMEOUT);
        Integer healthCheckHealthyThreshold = getConfig(HEALTH_CHECK_HEALTHY_THRESHOLD);
        Integer healthCheckUnhealthyThreshold = getConfig(HEALTH_CHECK_UNHEALTHY_THRESHOLD);

        Group serverPool = getConfig(SERVER_POOL);
        LOG.debug("Creating new ELB '" + elbName + "', for server-pool " + serverPool);

        String identity = loc.getIdentity();
        String credential = loc.getCredential();
        LoadBalancerServiceContext loadBalancerServiceContext = null;
        try {
            loadBalancerServiceContext = newLoadBalancerServiceContext(identity, credential);
            // TODO would be cool to use the loadbalancer abstraction but it doesn't like an empty collection of nodes
            ELBApi elbApi = api(loadBalancerServiceContext);
            String elbDnsName = elbApi.getLoadBalancerApi()
                    .createListeningInAvailabilityZones(
                            elbName,
                            Listener.builder()
                                    .protocol(Protocol.HTTP)
                                    .instancePort(instancePort)
                                    .port(loadBalancerPort)
                                    .build(),
                            availabilityZoneNames);

            sensors().set(Attributes.HOSTNAME, elbDnsName);
        } finally {
            Closeables2.closeQuietly(loadBalancerServiceContext.unwrap());
        }
    }

    private LoadBalancerServiceContext newLoadBalancerServiceContext(String identity, String credential) {
        return ContextBuilder.newBuilder("aws-elb")
                .credentials(identity, credential)
                .buildView(typeToken(LoadBalancerServiceContext.class));
    }

    protected void reinitLoadBalancer() {
        JcloudsLocation loc = getLocation();
        String elbName = checkNotNull(checkLoadBalancerName(LOAD_BALANCER_NAME), LOAD_BALANCER_NAME.getName());

        int loadBalancerPort = getRequiredConfig(LOAD_BALANCER_PORT);
        int instancePort = getRequiredConfig(INSTANCE_PORT);
        String instanceProtocol = getRequiredConfig(INSTANCE_PROTOCOL);
        String elbScheme = getConfig(LOAD_BALANCER_SCHEME);
        String loadBalancerProtocol = getRequiredConfig(LOAD_BALANCER_PROTOCOL);
        Collection<String> securityGroups = getConfig(LOAD_BALANCER_SECURITY_GROUPS);
        Set<String> subnets = (getConfig(LOAD_BALANCER_SUBNETS) != null) ? ImmutableSet.copyOf(getConfig(LOAD_BALANCER_SUBNETS)) : ImmutableSet.<String>of();
        String sslCertificateId = getConfig(SSL_CERTIFICATE_ID);
        Set<String> availabilityZoneNames = getAvailabilityZones(loc);
        Boolean healthCheckEnabled = getConfig(HEALTH_CHECK_ENABLED);
        Integer healthCheckInterval = getConfig(HEALTH_CHECK_INTERVAL);
        Integer healthCheckTimeout = getConfig(HEALTH_CHECK_TIMEOUT);
        Integer healthCheckHealthyThreshold = getConfig(HEALTH_CHECK_HEALTHY_THRESHOLD);
        Integer healthCheckUnhealthyThreshold = getConfig(HEALTH_CHECK_UNHEALTHY_THRESHOLD);

        LOG.debug("Re-initialising existing ELB: " + elbName);

        LoadBalancerServiceContext loadBalancerServiceContext = null;
        try {
            loadBalancerServiceContext = newLoadBalancerServiceContext(loc.getIdentity(), loc.getCredential());
            ELBApi api = api(loadBalancerServiceContext);

            LoadBalancer loadBalancer = api.getLoadBalancerApi().get(elbName);
            Scheme oldScheme = loadBalancer.getScheme().orNull();
            if (oldScheme != null && !oldScheme.value().equals(elbScheme)) {
                LOG.warn("Existing ELB {} scheme ({}) is different from configuration ({}); cannot modify; continuing",
                        new Object[] {elbName, oldScheme, elbScheme});
            }

            /*
            // Set the availability zones
            Set<String> oldAvailabilityZoneNames = loadBalancer.getAvailabilityZones();
            Set<String> oldAvailabilityZoneNamesSet = ImmutableSet.copyOf(oldAvailabilityZoneNames);
            Set<String> removedAvailabilityZoneNames = Sets.difference(oldAvailabilityZoneNamesSet, availabilityZoneNames);
            Set<String> addedAvailabilityZoneNames = Sets.difference(availabilityZoneNames, oldAvailabilityZoneNamesSet);
            if (!addedAvailabilityZoneNames.isEmpty()) {
                api.getAvailabilityZoneApi().addAvailabilityZonesToLoadBalancer(addedAvailabilityZoneNames, elbName);
            }
            if (!removedAvailabilityZoneNames.isEmpty()) {
                api.getAvailabilityZoneApi().removeAvailabilityZonesFromLoadBalancer(addedAvailabilityZoneNames, elbName);
            }

            // Set the security groups
            // TODO Not changing security groups if config is empty, because calling applySecurityGroups with an empty list causes:
            //      Caused by: AmazonServiceException: Status Code: 400, AWS Service: AmazonElasticLoadBalancing, AWS Request ID: f0661b5b-30ee-11e3-bd02-232415f6851f, AWS Error Code: ValidationError, AWS Error Message: 1 validation error detected: Value null at 'securityGroups' failed to satisfy constraint: Member must not be null
            // if (securityGroups != null && securityGroups.size() > 0) {
            //    api.*.applySecurityGroupsToLoadBalancer(securityGroups != null ? securityGroups : ImmutableList.<String>of(), elbName);
            //}

            // Set the subnets
            // Set<String> oldSubnets = ImmutableSet.copyOf(loadBalancer.getSubnets());
            // Set<String> removedSubnets = Sets.difference(oldSubnets, subnets);
            // Set<String> addedSubnets = Sets.difference(subnets, oldSubnets);
            //if (!addedSubnets.isEmpty()) {
            //    api.getSubnetApi().attachLoadBalancerToSubnets(addedSubnets, elbName);
            //}
            //if (!removedSubnets.isEmpty()) {
            //    api.getSubnetApi().detachLoadBalancerFromSubnets(removedSubnets, elbName);
            //}
            // Remove any old listeners, and add the new one

            Set<ListenerWithPolicies> listenerWithPolicies = loadBalancer.getListeners();
            Set<Integer> listenerPorts = Sets.newHashSet();
            for (ListenerWithPolicies listenerWithPolicy : listenerWithPolicies) {
                listenerPorts.add(listenerWithPolicy.getPort());
            }
            if (listenerPorts.size() > 0) {
                api.getListenerApi().deleteListeners(deleteListeners, elbName);
            }
            */

            Listener listener = Listener.builder()
                    .protocol(Protocol.valueOf(loadBalancerProtocol))
                    .instancePort(instancePort)
                    .instanceProtocol(Protocol.valueOf(instanceProtocol))
                    .port(loadBalancerPort)
                    .build();
            if (Strings.isNonBlank(sslCertificateId)) listener.toBuilder().SSLCertificateId(sslCertificateId);
            api.getLoadBalancerApi().createListeningInSubnetsAssignedToSecurityGroups(elbName, subnets, securityGroups != null ? securityGroups : ImmutableList.<String>of());

            // Reset the health check
            /*
            HealthCheck oldHealthCheck = loadBalancer.getHealthCheck();
            if (healthCheckEnabled != null && healthCheckEnabled) {
                // TODO remove duplication from createLoadBalancer; would require passing in a huge amount of config
                // as parameters, or the shared method reading the config itself
                String targetTemplate = getConfig(HEALTH_CHECK_TARGET);
                Map<String, Object> substitutions = ImmutableMap.<String, Object>builder()
                        .put("instancePort", instancePort)
                        .put("instanceProtocol", instanceProtocol)
                        .build();
                String target = TemplateProcessor.processTemplateContents(targetTemplate, substitutions);

                HealthCheck healthCheck = HealthCheck.builder()
                        .target(target)
                        .interval(healthCheckInterval)
                        .timeout(healthCheckTimeout)
                        .healthyThreshold(healthCheckHealthyThreshold)
                        .unhealthyThreshold(healthCheckUnhealthyThreshold)
                        .build();

                api.getHealthCheckApi().configureHealthCheck(healthCheck, elbName);
            } else {
                // TODO Not removing old health check because passing in null for ConfigureHealthCheckRequest gives:
                //      Caused by: AmazonServiceException: Status Code: 400, AWS Service: AmazonElasticLoadBalancing, AWS Request ID: 47a2b87d-30ef-11e3-bd82-6571b2f68bd5, AWS Error Code: ValidationError, AWS Error Message: 1 validation error detected: Value null at 'healthCheck' failed to satisfy constraint: Member must not be null
                if (oldHealthCheck != null) {
                    LOG.warn("Existing ELB {} (in {}) health check ({}) not removed (no new health check wanted; continuing",
                            new Object[] {elbName, this, oldHealthCheck});
                }
            }
            */

            sensors().set(Attributes.HOSTNAME, loadBalancer.getDnsName());
            } finally {
            Closeables2.closeQuietly(loadBalancerServiceContext.unwrap());
        }

    }

    @Override
    public void deleteLoadBalancer() {
        String elbName = checkNotNull(checkLoadBalancerName(LOAD_BALANCER_NAME), "elbName");
        deleteLoadBalancer(elbName);
    }

    protected String generateUnusedElbName() {
        int maxAttempts = 100;
        String elbName = null;
        ConfigBag setup = config().getBag();
        for (int i = 0; i < maxAttempts; i++) {
            elbName = new JcloudsMachineNamer().generateNewGroupId(setup);
            boolean exists = doesLoadBalancerExist(elbName);
            if (!exists) return elbName;
            LOG.debug("Auto-generated ELB name {} in {} conflicts with existing; trying again (attempt {}) to generate name", new Object[] {elbName, this, (i+2)});
        }
        throw new IllegalStateException("Failed to unused auto-genreate ELB name after "+maxAttempts+" attempts (last attempt was "+elbName+")");
    }

    protected boolean doesLoadBalancerExist(String elbName) {
        JcloudsLocation loc = getLocation();

        LoadBalancerServiceContext loadBalancerServiceContext = null;
        try {
            loadBalancerServiceContext = newLoadBalancerServiceContext(loc.getIdentity(), loc.getCredential());
            return loadBalancerServiceContext.getLoadBalancerService().getLoadBalancerMetadata(elbName) != null;
        } finally {
            Closeables2.closeQuietly(loadBalancerServiceContext.unwrap());
        }
    }

    protected void deleteLoadBalancer(String elbName) {
        JcloudsLocation loc = getLocation();
        LOG.debug("Deleting ELB: " + elbName);

        LoadBalancerServiceContext loadBalancerServiceContext = null;
        try {
            loadBalancerServiceContext = newLoadBalancerServiceContext(loc.getIdentity(), loc.getCredential());
            loadBalancerServiceContext.getLoadBalancerService().destroyLoadBalancer(elbName);
        } finally {
            Closeables2.closeQuietly(loadBalancerServiceContext.unwrap());
        }
    }

    private Set<String> getAvailabilityZones(JcloudsLocation loc) {
        Collection<String> availabilityZones = getConfig(AVAILABILITY_ZONES);
        if (availabilityZones == null) {
            String locName = loc.getRegion();
            if (isAvailabilityZone(locName)) {
                return ImmutableSet.of(locName); // location is a single availability zone
            } else {
                String regionName = getRegionName(loc);
                AWSEC2Api ec2api = loc.getComputeService().getContext().unwrapApi(AWSEC2Api.class);
                Set<AvailabilityZoneInfo> zones = ec2api.getAvailabilityZoneAndRegionApi().get().describeAvailabilityZonesInRegion(regionName);

                Set<String> result = Sets.newLinkedHashSet();
                for (AvailabilityZoneInfo zone : zones) {
                    result.add(zone.getZone());
                }
                return result;
            }
        }
        return ImmutableSet.copyOf(availabilityZones);
    }

    @Override
    protected String inferProtocol() {
        String result = config().get(LOAD_BALANCER_PROTOCOL);
        return (result == null ? result : result.toLowerCase());
    }

    @Override
    protected String inferUrl() {
        String protocol = inferProtocol();
        String domain = getAttribute(Attributes.HOSTNAME);
        Integer port = config().get(LOAD_BALANCER_PORT);
        return protocol + "://" + domain + (port == null ? "" : ":"+port);
    }

    @Override
    public void restart() {
        // no-op
    }

    @Override
    protected void reconfigureService() {
        // see #reload(); no prep required in reconfigureService
    }

    private ELBApi api(LoadBalancerServiceContext loadBalancerServiceContext) {
        return loadBalancerServiceContext.unwrapApi(ELBApi.class);
    }

    @Override
    protected String getAddressOfEntity(Entity member) {
        JcloudsSshMachineLocation machine = (JcloudsSshMachineLocation) Iterables.find(member.getLocations(),
                Predicates.instanceOf(JcloudsSshMachineLocation.class), null);

        if (machine != null && machine.getJcloudsId() != null) {
            return machine.getJcloudsId().split("\\/")[1];
        } else {
            LOG.error("Unable to construct JcloudsId representation for {}; skipping in {}", new Object[] { member, this });
            return null;
        }
    }

    protected String getRegionName(JcloudsLocation loc) {
        String regionName = loc.getRegion();
        if (Strings.isNonBlank(regionName) && Character.isLetter(regionName.charAt(regionName.length()-1))) {
            // it's an availability zone; strip off the letter suffix
            regionName = regionName.substring(0, regionName.length()-1);
        }
        return regionName;
    }

    protected boolean isAvailabilityZone(String name) {
        return Strings.isNonBlank(name) && Character.isLetter(name.charAt(name.length()-1));
    }

    protected <T> T getRequiredConfig(ConfigKey<T> key) {
        return checkNotNull(getConfig(key), key.getName());
    }

    protected <T> T getRequiredConfig(HasConfigKey<T> key) {
        return checkNotNull(getConfig(key), key.getConfigKey().getName());
    }

    protected JcloudsLocation getLocation() {
        JcloudsLocation result = getAttribute(JCLOUDS_LOCATION);
        checkNotNull(result, "JcloudsLocation not set - was ELB started, or has it been stopped?");
        return result;
    }

    protected JcloudsLocation inferLocation(@Nullable Collection<? extends Location> locations) {
        if (locations==null || locations.isEmpty()) locations = getLocations();
        locations = Locations.getLocationsCheckingAncestors(locations, this);

        Maybe<JcloudsLocation> result = Machines.findUniqueElement(locations, JcloudsLocation.class);
        if (result.isPresent()) {
            return result.get();
        }

        if (locations == null || locations.isEmpty()) {
            throw new IllegalArgumentException("No locations specified when starting "+this);
        } else if (locations.size() != 1 || Iterables.getOnlyElement(locations)==null) {
            throw new IllegalArgumentException("Ambiguous locations detected when starting "+this+": "+locations);
        } else {
            throw new IllegalArgumentException("No matching JcloudsLocation when starting "+this+": "+locations);
        }
    }
}
