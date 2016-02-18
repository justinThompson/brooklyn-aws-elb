package brooklyn.entity.proxy.aws;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.config.ConfigKey;
import org.apache.brooklyn.config.ConfigKey.HasConfigKey;
import org.apache.brooklyn.core.entity.Attributes;
import org.apache.brooklyn.core.entity.lifecycle.Lifecycle;
import org.apache.brooklyn.entity.proxy.AbstractNonProvisionedControllerImpl;
import org.apache.brooklyn.location.jclouds.JcloudsLocation;
import org.apache.brooklyn.location.jclouds.JcloudsMachineNamer;
import org.apache.brooklyn.location.jclouds.JcloudsSshMachineLocation;
import org.apache.brooklyn.util.core.config.ConfigBag;
import org.apache.brooklyn.util.core.text.TemplateProcessor;
import org.apache.brooklyn.util.text.Strings;
import org.jclouds.aws.ec2.AWSEC2Api;
import org.jclouds.ec2.domain.AvailabilityZoneInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.elasticloadbalancing.AmazonElasticLoadBalancingClient;
import com.amazonaws.services.elasticloadbalancing.model.ApplySecurityGroupsToLoadBalancerRequest;
import com.amazonaws.services.elasticloadbalancing.model.AttachLoadBalancerToSubnetsRequest;
import com.amazonaws.services.elasticloadbalancing.model.ConfigureHealthCheckRequest;
import com.amazonaws.services.elasticloadbalancing.model.CreateLoadBalancerListenersRequest;
import com.amazonaws.services.elasticloadbalancing.model.CreateLoadBalancerRequest;
import com.amazonaws.services.elasticloadbalancing.model.CreateLoadBalancerResult;
import com.amazonaws.services.elasticloadbalancing.model.DeleteLoadBalancerListenersRequest;
import com.amazonaws.services.elasticloadbalancing.model.DeleteLoadBalancerRequest;
import com.amazonaws.services.elasticloadbalancing.model.DeregisterInstancesFromLoadBalancerRequest;
import com.amazonaws.services.elasticloadbalancing.model.DescribeLoadBalancersRequest;
import com.amazonaws.services.elasticloadbalancing.model.DescribeLoadBalancersResult;
import com.amazonaws.services.elasticloadbalancing.model.DetachLoadBalancerFromSubnetsRequest;
import com.amazonaws.services.elasticloadbalancing.model.DisableAvailabilityZonesForLoadBalancerRequest;
import com.amazonaws.services.elasticloadbalancing.model.EnableAvailabilityZonesForLoadBalancerRequest;
import com.amazonaws.services.elasticloadbalancing.model.HealthCheck;
import com.amazonaws.services.elasticloadbalancing.model.Instance;
import com.amazonaws.services.elasticloadbalancing.model.Listener;
import com.amazonaws.services.elasticloadbalancing.model.ListenerDescription;
import com.amazonaws.services.elasticloadbalancing.model.LoadBalancerDescription;
import com.amazonaws.services.elasticloadbalancing.model.LoadBalancerNotFoundException;
import com.amazonaws.services.elasticloadbalancing.model.RegisterInstancesWithLoadBalancerRequest;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
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
     *  - Go through com.amazonaws.services.elasticloadbalancing.AmazonElasticLoadBalancingClient carefully, to see nothing else is missed
     */
    
//    does rebind() remove old instances?!
//    cassandra load balancing?
    
    private static final Logger LOG = LoggerFactory.getLogger(ElbControllerImpl.class);

    private JcloudsLocation loc;
    
    @Override
    public void start(Collection<? extends Location> locations) {
        super.start(locations);
        checkArgument(locations.size() == 1, "start must have exactly one location, but given %s (%s)", locations.size(), locations);
        Location onlyloc = Iterables.getOnlyElement(locations);
        checkArgument(onlyloc instanceof JcloudsLocation, "start must have exactly one location, of type JcloudsLocation, but given %s (%s)", (onlyloc != null ? onlyloc.getClass() : null), onlyloc);
        loc = (JcloudsLocation) onlyloc;
        checkArgument("aws-ec2".equals(loc.getProvider()), "start must have exactly one jclouds location for aws-ec2, but given provider %s (%s)", loc.getProvider(), loc);
        
        startLoadBalancer();
        isActive = true;
    }

    @Override
    public void stop() {
        // TODO call deleteLoadBalancer()?
        // TODO record service-up?
        loc = null;
    }

    @Override
    public void restart() {
        // no-op
    }

    @Override
    protected void reconfigureService() {
        // see #reload(); no prep required in reconfigureService
    }

    @Override
    public void reload() {
        if (loc == null) {
            // TODO Guard this better, in case of concurrent calls
            LOG.info("Not reloading ELB configuration, because ElbController is stopped");
            return;
        }
        String elbName = getAttribute(LOAD_BALANCER_NAME);
        Set<Instance> instances = Sets.newLinkedHashSet();
        for (String address : serverPoolAddresses) {
            Instance instance = new Instance(address);
            instances.add(instance);
        }

        AmazonElasticLoadBalancingClient client = newClient(loc);
        try {
            DescribeLoadBalancersResult loadBalancers = client.describeLoadBalancers(new DescribeLoadBalancersRequest(ImmutableList.of(elbName)));
            List<LoadBalancerDescription> loadBalancerDescriptions = loadBalancers.getLoadBalancerDescriptions();
            Set<Instance> oldInstances = ImmutableSet.copyOf(loadBalancerDescriptions.get(0).getInstances());
            Set<Instance> removedInstances = Sets.difference(oldInstances, instances);
            Set<Instance> addedInstances = Sets.difference(instances, oldInstances);
            
            if (!addedInstances.isEmpty()) {
                RegisterInstancesWithLoadBalancerRequest registerRequest = new RegisterInstancesWithLoadBalancerRequest(elbName, ImmutableList.copyOf(addedInstances));
                client.registerInstancesWithLoadBalancer(registerRequest);
            }
            if (!removedInstances.isEmpty()) {
                DeregisterInstancesFromLoadBalancerRequest deregisterRequest = new DeregisterInstancesFromLoadBalancerRequest(elbName, ImmutableList.copyOf(removedInstances));
                client.deregisterInstancesFromLoadBalancer(deregisterRequest);
            }
        } finally {
            if (client != null) client.shutdown();
        }
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

    protected void startLoadBalancer() {
        String elbName = getAttribute(LOAD_BALANCER_NAME);
        
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
        
        AmazonElasticLoadBalancingClient client = newClient(loc);
        try {
            CreateLoadBalancerRequest createLoadBalancerRequest = new CreateLoadBalancerRequest();

            createLoadBalancerRequest.setLoadBalancerName(elbName);
            createLoadBalancerRequest.setAvailabilityZones(availabilityZoneNames);
            if (Strings.isNonBlank(elbScheme)) createLoadBalancerRequest.setScheme(elbScheme);
            if (securityGroups != null) createLoadBalancerRequest.setSecurityGroups(securityGroups);
            if (subnets != null) createLoadBalancerRequest.setSubnets(subnets);
            
            Listener listener = new Listener();
            listener.setProtocol(loadBalancerProtocol);
            listener.setLoadBalancerPort(loadBalancerPort);
            listener.setInstancePort(instancePort);
            listener.setInstanceProtocol(instanceProtocol);
            if (Strings.isNonBlank(sslCertificateId)) listener.setSSLCertificateId(sslCertificateId);
            createLoadBalancerRequest.setListeners(ImmutableList.of(listener));

            CreateLoadBalancerResult result = client.createLoadBalancer(createLoadBalancerRequest);
            
            if (healthCheckEnabled != null && healthCheckEnabled) {
                String targetTemplate = getConfig(HEALTH_CHECK_TARGET);
                Map<String, Object> substitutions = ImmutableMap.<String, Object>builder()
                        .put("instancePort", instancePort)
                        .put("instanceProtocol", instanceProtocol)
                        .build();
                String target = TemplateProcessor.processTemplateContents(targetTemplate, substitutions);

                HealthCheck healthCheck = new HealthCheck()
                        .withTarget(target)
                        .withInterval(healthCheckInterval)
                        .withTimeout(healthCheckTimeout)
                        .withHealthyThreshold(healthCheckHealthyThreshold)
                        .withUnhealthyThreshold(healthCheckUnhealthyThreshold);
                
                ConfigureHealthCheckRequest healthCheckReq = new ConfigureHealthCheckRequest()
                        .withHealthCheck(healthCheck)
                        .withLoadBalancerName(elbName);
                client.configureHealthCheck(healthCheckReq);
            }
            
            sensors().set(Attributes.HOSTNAME, result.getDNSName());
            
        } finally {
            if (client != null) client.shutdown();
        }
    }

    protected void reinitLoadBalancer() {
        String elbName = checkNotNull(getAttribute(LOAD_BALANCER_NAME), LOAD_BALANCER_NAME.getName());

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
        
        AmazonElasticLoadBalancingClient client = newClient(loc);
        try {
            // Find out about existing load balancer, so can clear+reset its configuration
            DescribeLoadBalancersResult loadBalancers = client.describeLoadBalancers(new DescribeLoadBalancersRequest(ImmutableList.of(elbName)));
            List<LoadBalancerDescription> loadBalancerDescriptions = loadBalancers.getLoadBalancerDescriptions();
            if (loadBalancerDescriptions.isEmpty()) {
                throw new IllegalStateException("No existing load balancer with name "+elbName);
            }
            LoadBalancerDescription loadBalancerDescription = loadBalancerDescriptions.get(0);
            
            // TODO Things we can't change: warn if different from configuration!
            // Do we just need to dig into API more?!
            String oldScheme = loadBalancerDescription.getScheme();
            if (!oldScheme.equals(elbScheme)) {
                LOG.warn("Existing ELB {} scheme ({}) is different from configuration ({}); cannot modify; continuing", 
                        new Object[] {elbName, oldScheme, elbScheme});
            }

            // Set the availability zones
            List<String> oldAvailabilityZoneNames = loadBalancerDescription.getAvailabilityZones();
            Set<String> oldAvailabilityZoneNamesSet = ImmutableSet.copyOf(oldAvailabilityZoneNames);
            Set<String> removedAvailabilityZoneNames = Sets.difference(oldAvailabilityZoneNamesSet, availabilityZoneNames);
            Set<String> addedAvailabilityZoneNames = Sets.difference(availabilityZoneNames, oldAvailabilityZoneNamesSet);
            if (!addedAvailabilityZoneNames.isEmpty()) {
                EnableAvailabilityZonesForLoadBalancerRequest enableAvailabilityZonesRequest = new EnableAvailabilityZonesForLoadBalancerRequest()
                        .withLoadBalancerName(elbName)
                        .withAvailabilityZones(addedAvailabilityZoneNames);
                client.enableAvailabilityZonesForLoadBalancer(enableAvailabilityZonesRequest);
            }
            if (!removedAvailabilityZoneNames.isEmpty()) {
                DisableAvailabilityZonesForLoadBalancerRequest disableAvailabilityZonesRequest = new DisableAvailabilityZonesForLoadBalancerRequest()
                        .withLoadBalancerName(elbName)
                        .withAvailabilityZones(removedAvailabilityZoneNames);
                client.disableAvailabilityZonesForLoadBalancer(disableAvailabilityZonesRequest);
            }
            
            // Set the security groups
            // TODO Not changing security groups if config is empty, because calling applySecurityGroups with an empty list causes:
            //      Caused by: AmazonServiceException: Status Code: 400, AWS Service: AmazonElasticLoadBalancing, AWS Request ID: f0661b5b-30ee-11e3-bd02-232415f6851f, AWS Error Code: ValidationError, AWS Error Message: 1 validation error detected: Value null at 'securityGroups' failed to satisfy constraint: Member must not be null
            if (securityGroups != null && securityGroups.size() > 0) {
                ApplySecurityGroupsToLoadBalancerRequest securityGroupsRequest = new ApplySecurityGroupsToLoadBalancerRequest()
                        .withLoadBalancerName(elbName)
                        .withSecurityGroups(securityGroups != null ? securityGroups : ImmutableList.<String>of());
                client.applySecurityGroupsToLoadBalancer(securityGroupsRequest);
            }

            // Set the subnets
            Set<String> oldSubnets = ImmutableSet.copyOf(loadBalancerDescription.getSubnets());
            Set<String> removedSubnets = Sets.difference(oldSubnets, subnets);
            Set<String> addedSubnets = Sets.difference(subnets, oldSubnets);
            if (!addedSubnets.isEmpty()) {
                AttachLoadBalancerToSubnetsRequest attachSubnetsRequest = new AttachLoadBalancerToSubnetsRequest()
                        .withLoadBalancerName(elbName)
                        .withSubnets(addedSubnets);
                client.attachLoadBalancerToSubnets(attachSubnetsRequest);
            }
            if (!removedSubnets.isEmpty()) {
                DetachLoadBalancerFromSubnetsRequest deattachSubnetsRequest = new DetachLoadBalancerFromSubnetsRequest()
                        .withLoadBalancerName(elbName)
                        .withSubnets(removedSubnets);
                client.detachLoadBalancerFromSubnets(deattachSubnetsRequest);
            }

            // Remove any old listeners, and add the new one
            List<ListenerDescription> listenerDescriptions = loadBalancerDescription.getListenerDescriptions();
            List<Integer> listenerPorts = Lists.newArrayList();
            for (ListenerDescription listenerDescription : listenerDescriptions) {
                listenerPorts.add(listenerDescription.getListener().getLoadBalancerPort());
            }
            if (listenerPorts.size() > 0) {
                DeleteLoadBalancerListenersRequest deleteListenersRequest = new DeleteLoadBalancerListenersRequest()
                        .withLoadBalancerName(elbName)
                        .withLoadBalancerPorts(listenerPorts);
                client.deleteLoadBalancerListeners(deleteListenersRequest);
            }

            Listener listener = new Listener();
            listener.setProtocol(loadBalancerProtocol);
            listener.setLoadBalancerPort(loadBalancerPort);
            listener.setInstancePort(instancePort);
            listener.setInstanceProtocol(instanceProtocol);
            if (Strings.isNonBlank(sslCertificateId)) listener.setSSLCertificateId(sslCertificateId);
            CreateLoadBalancerListenersRequest createListenersRequest = new CreateLoadBalancerListenersRequest()
                .withLoadBalancerName(elbName)
                .withListeners(listener);
            client.createLoadBalancerListeners(createListenersRequest);

            // Reset the health check
            HealthCheck oldHealthCheck = loadBalancerDescription.getHealthCheck();
            if (healthCheckEnabled != null && healthCheckEnabled) {
                // TODO remove duplication from createLoadBalancer; would require passing in a huge amount of config
                // as parameters, or the shared method reading the config itself
                String targetTemplate = getConfig(HEALTH_CHECK_TARGET);
                Map<String, Object> substitutions = ImmutableMap.<String, Object>builder()
                        .put("instancePort", instancePort)
                        .put("instanceProtocol", instanceProtocol)
                        .build();
                String target = TemplateProcessor.processTemplateContents(targetTemplate, substitutions);

                HealthCheck healthCheck = new HealthCheck()
                        .withTarget(target)
                        .withInterval(healthCheckInterval)
                        .withTimeout(healthCheckTimeout)
                        .withHealthyThreshold(healthCheckHealthyThreshold)
                        .withUnhealthyThreshold(healthCheckUnhealthyThreshold);
                
                ConfigureHealthCheckRequest healthCheckReq = new ConfigureHealthCheckRequest()
                        .withLoadBalancerName(elbName)
                        .withHealthCheck(healthCheck);
                client.configureHealthCheck(healthCheckReq);
            } else {
                // TODO Not removing old health check because passing in null for ConfigureHealthCheckRequest gives:
                //      Caused by: AmazonServiceException: Status Code: 400, AWS Service: AmazonElasticLoadBalancing, AWS Request ID: 47a2b87d-30ef-11e3-bd82-6571b2f68bd5, AWS Error Code: ValidationError, AWS Error Message: 1 validation error detected: Value null at 'healthCheck' failed to satisfy constraint: Member must not be null
                if (oldHealthCheck != null) {
                    LOG.warn("Existing ELB {} (in {}) health check ({}) not removed (no new health check wanted; continuing", 
                            new Object[] {elbName, this, oldHealthCheck});
                }
            }
            
            sensors().set(Attributes.HOSTNAME, loadBalancerDescription.getDNSName());
            
        } finally {
            if (client != null) client.shutdown();
        }
    }


    @Override
    public void deleteLoadBalancer() {
        String elbName = checkNotNull(getAttribute(LOAD_BALANCER_NAME), "elbName");
        deleteLoadBalancer(elbName);
        sensors().set(Attributes.SERVICE_STATE_ACTUAL, Lifecycle.DESTROYED);
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
        AmazonElasticLoadBalancingClient client = newClient(loc);
        try {
            DescribeLoadBalancersResult loadBalancers = client.describeLoadBalancers(new DescribeLoadBalancersRequest(ImmutableList.of(elbName)));
            List<LoadBalancerDescription> loadBalancerDescriptions = loadBalancers.getLoadBalancerDescriptions();
            return loadBalancerDescriptions.size() > 0;
        } catch (LoadBalancerNotFoundException e) {
            LOG.trace("Load balancer {} not found when checking existance: {}", elbName, e);
            return false;
        } finally {
            if (client != null) client.shutdown();
        }
    }

    protected void deleteLoadBalancer(String elbName) {
        AmazonElasticLoadBalancingClient client = newClient(loc);
        try {
            DeleteLoadBalancerRequest deleteLoadBalancerRequest = new DeleteLoadBalancerRequest(elbName);
            client.deleteLoadBalancer(deleteLoadBalancerRequest);
        } finally {
            if (client != null) client.shutdown();
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
    
    protected AmazonElasticLoadBalancingClient newClient(JcloudsLocation loc) {
        String regionName = getRegionName(loc);
        AWSCredentials awsCredentials = new BasicAWSCredentials(loc.getIdentity(), loc.getCredential());
        AmazonElasticLoadBalancingClient client = new AmazonElasticLoadBalancingClient(awsCredentials);
        
        Region targetRegion = Region.getRegion(Regions.fromName(regionName));
        client.setRegion(targetRegion);
        
        return client;
    }
    
    protected <T> T getRequiredConfig(ConfigKey<T> key) {
        return checkNotNull(getConfig(key), key.getName());
    }
    
    protected <T> T getRequiredConfig(HasConfigKey<T> key) {
        return checkNotNull(getConfig(key), key.getConfigKey().getName());
    }
    
}
