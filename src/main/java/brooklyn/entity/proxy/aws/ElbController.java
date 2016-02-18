package brooklyn.entity.proxy.aws;

import java.util.Collection;

import com.google.common.reflect.TypeToken;

import brooklyn.config.ConfigKey;
import brooklyn.entity.annotation.Effector;
import brooklyn.entity.basic.Attributes;
import brooklyn.entity.basic.ConfigKeys;
import brooklyn.entity.proxy.AbstractNonProvisionedController;
import brooklyn.entity.proxying.ImplementedBy;
import brooklyn.event.AttributeSensor;
import brooklyn.event.basic.BasicAttributeSensorAndConfigKey;

@ImplementedBy(ElbControllerImpl.class)
public interface ElbController extends AbstractNonProvisionedController {

    AttributeSensor<String> HOSTNAME = Attributes.HOSTNAME;

    ConfigKey<Boolean> BIND_TO_EXISTING = ConfigKeys.newBooleanConfigKey(
            "aws.elb.bindToExisting", 
            "Whether to bind to an existing load balance, or create a new one", 
            false);
    
    ConfigKey<Boolean> REPLACE_EXISTING = ConfigKeys.newBooleanConfigKey(
            "aws.elb.replaceExisting", 
            "Whether to replace an existing load balance (if one exists with this name), or fail if one already exists",
            false);
    
    BasicAttributeSensorAndConfigKey<String> LOAD_BALANCER_NAME = new BasicAttributeSensorAndConfigKey(String.class, "aws.elb.loadBalancerName", "The ELB name", null);
    
    ConfigKey<Integer> LOAD_BALANCER_PORT = ConfigKeys.newIntegerConfigKey("aws.elb.loadBalancerPort", "The ELB port", 80);
    
    ConfigKey<String> LOAD_BALANCER_PROTOCOL = ConfigKeys.newStringConfigKey(
            "aws.elb.loadBalancerProtocol", "The load-balancer transport protocol to use for routing (HTTP, HTTPS, TCP, or SSL)", "HTTP");

    ConfigKey<String> SSL_CERTIFICATE_ID = ConfigKeys.newStringConfigKey(
            "aws.elb.sslCertificateId", "The ARN string of the server certificate", null);
    
    ConfigKey<Integer> INSTANCE_PORT = ConfigKeys.newIntegerConfigKey("aws.elb.instancePort", "The port for instances being balanced", 8080);

    ConfigKey<String> INSTANCE_PROTOCOL = ConfigKeys.newStringConfigKey(
            "aws.elb.instanceProtocol", "The protocol for routing traffic to back-end instances (HTTP, HTTPS, TCP, or SSL)", "HTTP");
            
    ConfigKey<Collection<String>> AVAILABILITY_ZONES = (ConfigKey) ConfigKeys.newConfigKey(
            new TypeToken<Collection<String>>() {}, 
            "aws.elb.availabilityZones", 
            "The availability zones to balance across (defaults to all in region)", 
            null);
    
    ConfigKey<String> LOAD_BALANCER_SCHEME = ConfigKeys.newStringConfigKey(
            "aws.elb.loadBalancerScheme", 
            "The type of a LoadBalancer. This option is only available for LoadBalancers attached to a Amazon VPC. "
                    + "By default, Elastic Load Balancer creates an internet-facing load balancer with publicly resolvable "
                    + "DNS name that resolves to public IP addresses. Specify the value internal for this option to create "
                    + "an internal load balancer with a DNS name that resolves to private IP addresses.", 
            null);

    ConfigKey<Collection<String>> LOAD_BALANCER_SECURITY_GROUPS = (ConfigKey) ConfigKeys.newConfigKey(
            Collection.class,
            "aws.elb.loadBalancerSecurityGroups", 
            "The security groups assigned to your LoadBalancer within your VPC",
            null);
    
    ConfigKey<Collection<String>> LOAD_BALANCER_SUBNETS = (ConfigKey) ConfigKeys.newConfigKey(
            Collection.class,
            "aws.elb.loadBalancerSubnets", 
            "A list of subnet IDs in your VPC to attach to your LoadBalancer",
            null);
    
    // http://docs.aws.amazon.com/AWSJavaSDK/latest/javadoc/com/amazonaws/services/elasticloadbalancing/model/HealthCheck.html#setTarget(java.lang.String)
    ConfigKey<String> HEALTH_CHECK_TARGET = ConfigKeys.newStringConfigKey(
            "aws.elb.healthCheck.target", "Specifies the instance being checked", "${instanceProtocol}:${instancePort?c}/");
    
    ConfigKey<Boolean> HEALTH_CHECK_ENABLED = ConfigKeys.newBooleanConfigKey(
            "aws.elb.healthCheck.enabled", "Whether to do health checks for the instances", true);
    
    ConfigKey<Integer> HEALTH_CHECK_INTERVAL = ConfigKeys.newIntegerConfigKey(
            "aws.elb.healthCheck.interval", "Approximate interval, in seconds, between health checks of an individual instance (1 to 300)", 20);
    
    ConfigKey<Integer> HEALTH_CHECK_TIMEOUT = ConfigKeys.newIntegerConfigKey(
            "aws.elb.healthCheck.timeout", "The amount of time, in seconds, during which no response means a failed health probe. This value must be less than the Interval value", 10);
    
    ConfigKey<Integer> HEALTH_CHECK_HEALTHY_THRESHOLD = ConfigKeys.newIntegerConfigKey(
            "aws.elb.healthCheck.healthyThreshold", "The number of consecutive health probe successes required before moving the instance to the Healthy state", 2);
    
    ConfigKey<Integer> HEALTH_CHECK_UNHEALTHY_THRESHOLD = ConfigKeys.newIntegerConfigKey(
            "aws.elb.healthCheck.unhealthyThreshold", "The number of consecutive health probe failures required before moving the instance to the Unhealthy state", 2);

    @Effector(description="Deletes the ELB")
    void deleteLoadBalancer();
}
