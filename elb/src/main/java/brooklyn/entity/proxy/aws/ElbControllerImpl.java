package brooklyn.entity.proxy.aws;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static org.jclouds.reflect.Reflection2.typeToken;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.Callable;

import javax.annotation.Nullable;

import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.entity.Group;
import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.config.ConfigKey;
import org.apache.brooklyn.core.entity.Attributes;
import org.apache.brooklyn.core.entity.BrooklynConfigKeys;
import org.apache.brooklyn.core.entity.lifecycle.Lifecycle;
import org.apache.brooklyn.core.entity.lifecycle.ServiceStateLogic;
import org.apache.brooklyn.core.entity.lifecycle.ServiceStateLogic.ServiceProblemsLogic;
import org.apache.brooklyn.core.feed.ConfigToAttributes;
import org.apache.brooklyn.core.feed.PollConfig;
import org.apache.brooklyn.core.location.Locations;
import org.apache.brooklyn.core.location.Machines;
import org.apache.brooklyn.entity.proxy.AbstractNonProvisionedControllerImpl;
import org.apache.brooklyn.feed.function.FunctionFeed;
import org.apache.brooklyn.feed.function.FunctionPollConfig;
import org.apache.brooklyn.location.jclouds.JcloudsLocation;
import org.apache.brooklyn.location.jclouds.JcloudsLocationConfig;
import org.apache.brooklyn.location.jclouds.JcloudsLocationCustomizer;
import org.apache.brooklyn.location.jclouds.JcloudsMachineNamer;
import org.apache.brooklyn.location.jclouds.JcloudsSshMachineLocation;
import org.apache.brooklyn.util.core.config.ConfigBag;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.guava.Maybe;
import org.apache.brooklyn.util.text.Strings;
import org.apache.brooklyn.util.time.Duration;
import org.jclouds.ContextBuilder;
import org.jclouds.compute.domain.Template;
import org.jclouds.elb.ELBApi;
import org.jclouds.elb.domain.HealthCheck;
import org.jclouds.elb.domain.Listener;
import org.jclouds.elb.domain.ListenerWithPolicies;
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

    /*
     * TODO More options that could be exposed:
     *
     *  - com.amazonaws.ClientConfiguration
     *    - protocol
     *    - maxConnections
     *    - userAgent
     *    - proxyHost
     *    - proxyPort
     *    - proxyUsername
     *    - proxyPassword
     *    - proxyDomain
     *    - proxyWorkstation
     *    - maxErrorRetry
     *    - socketTimeout
     *    - connectionTimeout
     *    - socketBufferSizeHints
     *  - com.amazonaws.handlers.RequestHandler (beforeRequest, afterRequest, afterError)
     *  - AppCookieStickinessPolicy
     *  - LBCookieStickinessPolicy
     *  - LoadBalancerPoliciesForBackendServer
     *  - LoadBalancerListenerSslCertificate
     *  - Go through com.amazonaws.services.elasticloadbalancing.AmazonElasticLoadBalancingClient carefully,
     *  to see nothing else is missed
     */


    // TODO Use the SoftwareProcess's approach of expectedState and serviceUpIndicators
    // TODO What should lifecycle be if effector deleteLoadBalancer is called?

    private static final Logger LOG = LoggerFactory.getLogger(ElbControllerImpl.class);

    private FunctionFeed elbAttributesFeed;

    @Override
    protected void doStart(Collection<? extends Location> locations) {
        ServiceProblemsLogic.clearProblemsIndicator(this, START);
        ServiceStateLogic.setExpectedState(this, Lifecycle.STARTING);
        try {
            JcloudsLocation loc = inferLocation(locations);

            checkArgument("aws-ec2".equals(loc.getProvider()),
                "start must have exactly one jclouds location for aws-ec2, but given provider %s (%s)",
                loc.getProvider(), loc);
            sensors().set(JCLOUDS_LOCATION, loc);

            ConfigToAttributes.apply(this, LOAD_BALANCER_NAME);

            startLoadBalancer();
            isActive = true;

            sensors().set(SERVICE_UP, true);
        } finally {
            ServiceStateLogic.setExpectedState(this, Lifecycle.RUNNING);
        }
    }

    @Override
    protected void postStart() {
        super.postStart();
        elbAttributesFeed = FunctionFeed.builder()
                .entity(this)
                .onlyIfServiceUp()
                .period(Duration.THIRTY_SECONDS)
                .poll(FunctionPollConfig.forSensor(PollConfig.NO_SENSOR).callable(new RepublishAttributesCallable()))
                .build();
    }

    protected void republishAttributes() {
        Lifecycle state = ServiceStateLogic.getActualState(this);
        if (state != Lifecycle.RUNNING) {
            return; // don't republish if stopping, to avoid possible "Failed to retrieve ELB" if it's already gone
        }

        LoadBalancerServiceContext loadBalancerServiceContext = null;
        try {
             JcloudsLocation location = inferLocation(ImmutableList.of(getLocation()));

            loadBalancerServiceContext = newLoadBalancerServiceContext(location.getIdentity(), location.getCredential());
            ELBApi elbApi = newELBApi(loadBalancerServiceContext);


            LoadBalancer elb = elbApi.getLoadBalancerApi().get(getConfig(LOAD_BALANCER_NAME));

            sensors().set(LOAD_BALANCER_SUBNETS, elb.getSubnets());
            sensors().set(LOAD_BALANCER_SECURITY_GROUPS, elb.getSecurityGroups());
            sensors().set(CANONICAL_HOSTED_ZONE_NAME, elb.getHostedZoneName().get());
            sensors().set(CANONICAL_HOSTED_ZONE_ID, elb.getHostedZoneId().get());
            sensors().set(VPC_ID, elb.getVPCId().get());
            ServiceProblemsLogic.clearProblemsIndicator(this, "attributes");
        } catch (Exception e) {
            ServiceProblemsLogic.updateProblemsIndicator(this, "attributes",
                    "Failed to retrieve ELB data: " + e.getMessage());
            sensors().set(LOAD_BALANCER_SUBNETS, null);
            sensors().set(LOAD_BALANCER_SECURITY_GROUPS, null);
            sensors().set(CANONICAL_HOSTED_ZONE_ID, null);
            sensors().set(CANONICAL_HOSTED_ZONE_NAME, null);
            sensors().set(VPC_ID, null);
        }
    }

    protected JcloudsLocation getLocation() {
        JcloudsLocation result = getAttribute(JCLOUDS_LOCATION);
        checkNotNull(result, "JcloudsLocation not set - was ELB started, or has it been stopped?");
        return result;
    }

    @Override
    protected void preStop() {
        if (elbAttributesFeed != null) {
            elbAttributesFeed.stop();
            elbAttributesFeed = null;
        }
        super.preStop();
    }

    @Override
    public void stop() {
        ServiceStateLogic.setExpectedState(this, Lifecycle.STOPPING);
        // TODO should we deleteLoadBalancer?
        String elbName = checkLoadBalancerName();
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
                LOG.warn("No location for ELB " + this + ", cannot reload");
                return;
            }
            String elbName = checkLoadBalancerName();
            Set<String> targetAddresses = super.getServerPoolAddresses();

            LoadBalancerServiceContext loadBalancerServiceContext = null;
            try {
                loadBalancerServiceContext = newLoadBalancerServiceContext(loc.getIdentity(), loc.getCredential());
                ELBApi elbApi = newELBApi(loadBalancerServiceContext);

                Set<String> instances = ImmutableSet.copyOf(targetAddresses);
                LoadBalancer loadBalancer = elbApi.getLoadBalancerApi().get(elbName);
                Set<String> oldInstances = ImmutableSet.copyOf(loadBalancer.getInstanceIds());
                Set<String> removedInstances = Sets.difference(oldInstances, instances);
                Set<String> addedInstances = Sets.difference(instances, oldInstances);

                if (!addedInstances.isEmpty()) {
                    elbApi.getInstanceApi().registerInstancesWithLoadBalancer(addedInstances, elbName);
                }
                if (!removedInstances.isEmpty()) {
                    elbApi.getInstanceApi().registerInstancesWithLoadBalancer(removedInstances, elbName);
                }
            } finally {
                Closeables2.closeQuietly(loadBalancerServiceContext.unwrap());
            }
        } catch (RuntimeException e) {
            LOG.warn("Problem reloading", e);
            throw Exceptions.propagate(e);
        }
    }
    
    @Override
    protected String getAddressOfEntity(Entity member) {
        JcloudsSshMachineLocation machine = (JcloudsSshMachineLocation) Iterables.find(member.getLocations(),
                Predicates.instanceOf(JcloudsSshMachineLocation.class), null);

        if (machine != null && machine.getJcloudsId() != null) {
            return machine.getJcloudsId().split("\\/")[1];
        } else {
            LOG.error("Unable to construct JcloudsId representation for {}; skipping in {}",
                    new Object[]{member, this});
            return null;
        }
    }
    ELBApi newELBApi(LoadBalancerServiceContext loadBalancerServiceContext) {
        return loadBalancerServiceContext.unwrapApi(ELBApi.class);
    }

    private String checkLoadBalancerName() {
        String elbName = getAttribute(LOAD_BALANCER_NAME);
        checkNotNull(elbName, "load balancer name must not be null");
        checkArgument(Strings.isNonBlank(elbName), "load balancer name must be non-blank");
        checkArgument(elbName.length() < 32);
        return elbName;
    }

    protected void startLoadBalancer() {
        String elbName = checkLoadBalancerName();

        if (getRequiredConfig(BIND_TO_EXISTING)) {
            reinitLoadBalancer();
        } else if (getRequiredConfig(REPLACE_EXISTING)) {
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
                    throw new IllegalStateException("Cannot create ELB "+elbName+" in " + this
                        + ", because already exists (consider using configuration "+REPLACE_EXISTING.getName()+")");
                }
            }

            createLoadBalancer(elbName);
        }
    }

    protected void createLoadBalancer(String elbName) {
        final JcloudsLocation loc = getLocation();
        Set<String> availabilityZoneNames = (getConfig(AVAILABILITY_ZONES) != null) ? ImmutableSet.copyOf(getConfig(AVAILABILITY_ZONES)) : ImmutableSet.<String>of();
        Set<String> subnets = (getConfig(LOAD_BALANCER_SUBNETS) != null) ? ImmutableSet.copyOf(getConfig(LOAD_BALANCER_SUBNETS)) : ImmutableSet.<String>of();

        Group serverPool = getConfig(SERVER_POOL);
        LOG.debug("Creating new ELB '" + elbName + "', for server-pool " + serverPool);

        String identity = loc.getIdentity();
        String credential = loc.getCredential();

        LoadBalancerServiceContext loadBalancerServiceContext = null;
        try {
            loadBalancerServiceContext = newLoadBalancerServiceContext(identity, credential);
            ELBApi elbApi = newELBApi(loadBalancerServiceContext);

            String elbDnsName;
            Listener listener = buildListener();

            if (!availabilityZoneNames.isEmpty()) {
                elbDnsName = elbApi.getLoadBalancerApi().createLoadBalancerInAvailabilityZones(elbName, ImmutableSet.<Listener>of(listener), availabilityZoneNames);
            } else if (!subnets.isEmpty()) {
                elbDnsName = elbApi.getLoadBalancerApi().createLoadBalancerInSubnets(elbName, ImmutableSet.<Listener>of(listener), subnets);
            } else {
                throw new RuntimeException("You must supply a list of availability zones or subnets");
            }

            LoadBalancer loadbalancer = elbApi.getLoadBalancerApi().get(elbName);
            configureListeners(elbApi, elbName, loadbalancer);
            configureHealthCheck(elbApi, elbName);
            configureSecurityGroups(elbApi, elbName);

            sensors().set(Attributes.HOSTNAME, elbDnsName);
        } finally {
            Closeables2.closeQuietly(loadBalancerServiceContext.unwrap());
        }
    }

    private void configureHealthCheck(ELBApi elbApi, String elbName) {
        Integer healthCheckInterval = getConfig(HEALTH_CHECK_INTERVAL);
        Integer healthCheckTimeout = getConfig(HEALTH_CHECK_TIMEOUT);
        Integer healthCheckHealthyThreshold = getConfig(HEALTH_CHECK_HEALTHY_THRESHOLD);
        Integer healthCheckUnhealthyThreshold = getConfig(HEALTH_CHECK_UNHEALTHY_THRESHOLD);
        String healthCheckTarget = getConfig(HEALTH_CHECK_TARGET);

        final LoadBalancer loadBalancer = elbApi.getLoadBalancerApi().get(elbName);
        final HealthCheck healthCheck = loadBalancer.getHealthCheck();

        final HealthCheck healthCheckConfig = HealthCheck.builder()
                .healthyThreshold(healthCheckHealthyThreshold != null ? healthCheckHealthyThreshold : healthCheck.getHealthyThreshold())
                .unhealthyThreshold(healthCheckUnhealthyThreshold != null ? healthCheckUnhealthyThreshold : healthCheck.getUnhealthyThreshold())
                .interval(healthCheckInterval != null ? healthCheckInterval : healthCheck.getInterval())
                .target(healthCheckTarget != null ? healthCheckTarget : healthCheck.getTarget())
                .timeout(healthCheckTimeout != null ? healthCheckTimeout : healthCheck.getTimeout())
                .build();

        elbApi.getHealthCheckApi().configureHealthCheck(elbName, healthCheckConfig);
    }

    LoadBalancerServiceContext newLoadBalancerServiceContext(String identity, String credential) {
        return ContextBuilder.newBuilder("aws-elb")
                .credentials(identity, credential)
                .buildView(typeToken(LoadBalancerServiceContext.class));
    }

    private Collection<JcloudsLocationCustomizer> getCustomizers() {
        // newInstance handles the case that provisioning properties is null.
        ConfigBag provisioningProperties = ConfigBag.newInstance(config().get(BrooklynConfigKeys.PROVISIONING_PROPERTIES));
        Collection<JcloudsLocationCustomizer> existingCustomizers =
            provisioningProperties.get(JcloudsLocationConfig.JCLOUDS_LOCATION_CUSTOMIZERS);
        return existingCustomizers;
    }


    protected void reinitLoadBalancer() {
        JcloudsLocation loc = getLocation();
        String elbName = checkNotNull(checkLoadBalancerName(), LOAD_BALANCER_NAME.getName());

        String elbScheme = getConfig(LOAD_BALANCER_SCHEME);

        LOG.debug("Re-initialising existing ELB: " + elbName);

        LoadBalancerServiceContext loadBalancerServiceContext = null;
        try {
            loadBalancerServiceContext = newLoadBalancerServiceContext(loc.getIdentity(), loc.getCredential());
            ELBApi elbApi = newELBApi(loadBalancerServiceContext);

            LoadBalancer loadBalancer = elbApi.getLoadBalancerApi().get(elbName);
            Scheme oldScheme = loadBalancer.getScheme().orNull();
            if (oldScheme != null && !oldScheme.value().equals(elbScheme)) {
                LOG.warn("Existing ELB {} scheme ({}) is different from configuration ({}); cannot modify; continuing",
                        new Object[]{elbName, oldScheme, elbScheme});
            }

            configureSecurityGroups(elbApi, elbName);

            configureAvailabilityZones(elbApi, elbName, loadBalancer);

            configureSubnets(elbApi, elbName, loadBalancer);

            configureListeners(elbApi, elbName, loadBalancer);
            configureHealthCheck(elbApi, elbName);

            sensors().set(Attributes.HOSTNAME, loadBalancer.getDnsName());
        } finally {
            Closeables2.closeQuietly(loadBalancerServiceContext.unwrap());
        }

    }

    private void configureSecurityGroups(ELBApi elbApi, String elbName) {
        final Collection<String> securityGroups = getConfig(LOAD_BALANCER_SECURITY_GROUPS);

        if (securityGroups != null && securityGroups.size() > 0) {
            elbApi.getLoadBalancerApi().applySecurityGroupsToLoadBalancer(elbName, securityGroups);
        }

    }
    // TODO READD THE AVAILABILITY ZONES -> getAvailabilityZones(loc)
    private void configureAvailabilityZones(ELBApi elbApi, String elbName, LoadBalancer loadBalancer) {
        Set<String> availabilityZoneNames = (getConfig(AVAILABILITY_ZONES) != null) ? ImmutableSet.copyOf(getConfig(AVAILABILITY_ZONES)) : ImmutableSet.<String>of();

        if (availabilityZoneNames.size() > 0) {

            Set<String> oldAvailabilityZoneNames = loadBalancer.getAvailabilityZones();
            Set<String> oldAvailabilityZoneNamesSet = ImmutableSet.copyOf(oldAvailabilityZoneNames);
            Set<String> removedAvailabilityZoneNames = Sets.difference(oldAvailabilityZoneNamesSet, availabilityZoneNames);
            Set<String> addedAvailabilityZoneNames = Sets.difference(availabilityZoneNames, oldAvailabilityZoneNamesSet);

            if (!addedAvailabilityZoneNames.isEmpty()) {
                elbApi.getAvailabilityZoneApi().addAvailabilityZonesToLoadBalancer(addedAvailabilityZoneNames, elbName);
            }
            if (!removedAvailabilityZoneNames.isEmpty()) {
                elbApi.getAvailabilityZoneApi().removeAvailabilityZonesFromLoadBalancer(removedAvailabilityZoneNames, elbName);
            }

        }
    }

    private void configureSubnets(ELBApi elbApi, String elbName, LoadBalancer loadBalancer) {
        Set<String> subnets = (getConfig(LOAD_BALANCER_SUBNETS) != null) ? ImmutableSet.copyOf(getConfig(LOAD_BALANCER_SUBNETS)) : ImmutableSet.<String>of();

        if (subnets.size() > 0) {
            Set<String> oldSubnets = ImmutableSet.copyOf(loadBalancer.getSubnets());
            Set<String> removedSubnets = Sets.difference(oldSubnets, subnets);
            Set<String> addedSubnets = Sets.difference(subnets, oldSubnets);
            if (!addedSubnets.isEmpty()) {
                elbApi.getSubnetApi().attachLoadBalancerToSubnets(elbName, addedSubnets);
            }
            if (!removedSubnets.isEmpty()) {
                elbApi.getSubnetApi().detachLoadBalancerFromSubnets(elbName, removedSubnets);
            }

        }
    }

    private void configureListeners(ELBApi elbApi, String elbName, LoadBalancer loadBalancer) {
        Set<ListenerWithPolicies> listenerWithPolicies = loadBalancer.getListeners();
        Set<Integer> listenerPorts = Sets.newHashSet();
        for (ListenerWithPolicies listenerWithPolicy : listenerWithPolicies) {
            listenerPorts.add(listenerWithPolicy.getPort());
        }

        if (!listenerPorts.isEmpty()) {
            elbApi.getLoadBalancerApi().deleteLoadBalancerListeners(elbName, listenerPorts);
        }
        Listener listener = buildListener();
        elbApi.getLoadBalancerApi().createLoadBalancerListeners(elbName, ImmutableSet.<Listener>of(listener));

    }

    private Listener buildListener() {
        int loadBalancerPort = getRequiredConfig(LOAD_BALANCER_PORT);
        int instancePort = getRequiredConfig(INSTANCE_PORT);
        String instanceProtocol = getRequiredConfig(INSTANCE_PROTOCOL);
        String loadBalancerProtocol = getRequiredConfig(LOAD_BALANCER_PROTOCOL);

        return Listener.builder()
                .protocol(Protocol.valueOf(loadBalancerProtocol))
                .instancePort(instancePort)
                .instanceProtocol(Protocol.valueOf(instanceProtocol))
                .port(loadBalancerPort)
                .build();
    }

    protected String generateUnusedElbName() {
        int maxAttempts = 100;
        String elbName = null;
        ConfigBag setup = config().getBag();
        for (int i = 0; i < maxAttempts; i++) {
            elbName = new JcloudsMachineNamer().generateNewGroupId(setup);
            boolean exists = doesLoadBalancerExist(elbName);
            if (!exists) return elbName;
            LOG.debug("Auto-generated ELB name {} in {} conflicts with existing; " +
                    "trying again (attempt {}) to generate name", new Object[]{elbName, this, (i+2)});
        }
        throw new IllegalStateException(
            "Failed to auto-generate unused ELB name after "+maxAttempts+" attempts (last attempt was "+elbName+")");
    }

    protected boolean doesLoadBalancerExist(String elbName) {
        JcloudsLocation loc = getLocation();
        String region = getRegionName(loc);

        LoadBalancerServiceContext loadBalancerServiceContext = newLoadBalancerServiceContext(loc.getIdentity(), loc.getCredential());
        try {
            return loadBalancerServiceContext.getLoadBalancerService().getLoadBalancerMetadata(region+"/"+elbName) != null;
        }
        finally {
            Closeables2.closeQuietly(loadBalancerServiceContext.unwrap());
        }

    }


    @Override
    public void deleteLoadBalancer() {
        String elbName = checkNotNull(checkLoadBalancerName(), "elbName");
        deleteLoadBalancer(elbName);
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
        return protocol + "://" + domain + (port == null ? "" : ":" + port);
    }

    @Override
    public void restart() {
        // no-op
    }

    @Override
    protected void reconfigureService() {
        // see #reload(); no prep required in reconfigureService
    }

    protected void deleteLoadBalancer(String elbName) {
        JcloudsLocation loc = getLocation();
        LOG.debug("Deleting ELB: " + elbName);
        preReleaseLocationCustomizations();

        LoadBalancerServiceContext loadBalancerServiceContext = null;
        try {
            loadBalancerServiceContext = newLoadBalancerServiceContext(loc.getIdentity(), loc.getCredential());
            loadBalancerServiceContext.getLoadBalancerService().destroyLoadBalancer(elbName);
        } finally {
            Closeables2.closeQuietly(loadBalancerServiceContext.unwrap());
        }
        postReleaseLocationCustomizations();

    }

    protected String getRegionName(JcloudsLocation loc) {
        String regionName = loc.getRegion();
        if (Strings.isNonBlank(regionName) && Character.isLetter(regionName.charAt(regionName.length()-1))) {
            // it's an availability zone; strip off the letter suffix
            regionName = regionName.substring(0, regionName.length()-1);
        }
        return regionName;
    }

    private void preReleaseLocationCustomizations() {
        final Collection<JcloudsLocationCustomizer> customizers = getCustomizers();
        if (customizers != null) {
            for (final JcloudsLocationCustomizer customizer : customizers) {
                class PreReleaser implements Runnable {
                    public void run() {
                        customizer.preRelease(null);
                    }
                }
                safelyRelease(new PreReleaser());
            }
        }
    }

    private void postReleaseLocationCustomizations() {
        final Collection<JcloudsLocationCustomizer> customizers = getCustomizers();
        if (customizers != null) {
            for (final JcloudsLocationCustomizer customizer : customizers) {
                class PostReleaser implements Runnable {
                    public void run() {
                        customizer.postRelease(null);
                    }
                }
                safelyRelease(new PostReleaser());
            }
        }
    }

    private void safelyRelease(Runnable releaser) {
        try {
            releaser.run();
        } catch (Throwable t) {
            if (Exceptions.isFatal(t)) {
                LOG.error("Caught fatal error during customizer release, this customizer is not compatible with "
                    + ElbController.class.getName());
                Exceptions.propagate(t);
            } else {
                LOG.info("Ignoring non-fatal exception during customizer release, this customizer may not be compatible with {}",
                    ElbController.class.getName());
                LOG.debug("Caught non-fatal exception during customizer release", t);
            }
        }
    }

    protected <T> T getRequiredConfig(ConfigKey<T> key) {
        return checkNotNull(getConfig(key), key.getName());
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

    private class RepublishAttributesCallable implements Callable<Void> {
        @Override
        public Void call() throws Exception {
            republishAttributes();
            return null;
        }
    }

}
