package brooklyn.entity.proxy.aws;

import java.util.Collection;

import org.apache.brooklyn.api.entity.ImplementedBy;
import org.apache.brooklyn.api.sensor.AttributeSensor;
import org.apache.brooklyn.config.ConfigKey;
import org.apache.brooklyn.core.annotation.Effector;
import org.apache.brooklyn.core.config.ConfigKeys;
import org.apache.brooklyn.core.entity.Attributes;
import org.apache.brooklyn.core.entity.lifecycle.Lifecycle;
import org.apache.brooklyn.core.entity.lifecycle.Lifecycle.Transition;
import org.apache.brooklyn.core.sensor.BasicAttributeSensorAndConfigKey;
import org.apache.brooklyn.core.sensor.Sensors;
import org.apache.brooklyn.entity.proxy.AbstractNonProvisionedController;
import org.apache.brooklyn.location.jclouds.JcloudsLocation;
import org.jclouds.compute.ComputeService;
import org.jclouds.compute.domain.Template;
import org.jclouds.compute.domain.TemplateBuilder;
import org.jclouds.compute.options.TemplateOptions;

import com.google.common.reflect.TypeToken;

/**
 * An entity to manage an AWS Elastic Load Balancer.
 * <p>
 * Use DSL with the {@code loadbalancer.serverpool} configuration key to define the target entities over which the
 * load balancer will balance requests.
 * <p>
 * Although no machine is provisioned when an ELB instance is created, this entity includes support for adding
 * {@link org.apache.brooklyn.location.jclouds.JcloudsLocationCustomizer}s to permit customization of the entity
 * based on its location. However, as no machine is provisioned, the only customization methods invoked are those
 * related to location templates,
 * <ul>
 * <li>{@link org.apache.brooklyn.location.jclouds.JcloudsLocationCustomizer#customize(JcloudsLocation, ComputeService, Template)},
 * <li>{@link org.apache.brooklyn.location.jclouds.JcloudsLocationCustomizer#customize(JcloudsLocation, ComputeService, TemplateBuilder)},
 * <li>{@link org.apache.brooklyn.location.jclouds.JcloudsLocationCustomizer#customize(JcloudsLocation, ComputeService, TemplateOptions)}.
 *</ul>
 * <p>
 * Example:
<pre>
name: elb-test
location: aws-ec2:us-east-1
services:

- type: brooklyn.entity.proxy.aws.ElbController
  name: ELB
  brooklyn.config:
    aws.elb.loadBalancerName: br-example-1
    aws.elb.availabilityZones: [us-east-1a, us-east-1b]
    aws.elb.loadBalancerProtocol: HTTP
    aws.elb.instancePort: 8080
    loadbalancer.serverpool: $brooklyn:entity("cluster")

- type: org.apache.brooklyn.entity.group.DynamicCluster
  id: cluster
  name: cluster
  brooklyn.config:
    initialSize: 1
    memberSpec:
      $brooklyn:entitySpec:
        type: org.apache.brooklyn.entity.software.base.EmptySoftwareProcess
 </pre>
 */
@ImplementedBy(ElbControllerImpl.class)
public interface ElbController extends AbstractNonProvisionedController {

    AttributeSensor<Lifecycle> SERVICE_STATE_ACTUAL = Attributes.SERVICE_STATE_ACTUAL;
    AttributeSensor<Transition> SERVICE_STATE_EXPECTED = Attributes.SERVICE_STATE_EXPECTED;

    AttributeSensor<Boolean> ELB_IS_RUNNING = Sensors.newBooleanSensor("aws.elb.isRunning",
            "Whether the ELB is confirmed as running");

    AttributeSensor<JcloudsLocation> JCLOUDS_LOCATION = Sensors.newSensor(
            JcloudsLocation.class,
            "aws.elb.jcloudsLocation",
            "AWS jclouds location");

    AttributeSensor<String> HOSTNAME = Attributes.HOSTNAME;

    ConfigKey<Boolean> BIND_TO_EXISTING = ConfigKeys.newBooleanConfigKey(
            "aws.elb.bindToExisting", 
            "Whether to bind to an existing load balancer or create a new one",
            false);
    
    ConfigKey<Boolean> REPLACE_EXISTING = ConfigKeys.newBooleanConfigKey(
            "aws.elb.replaceExisting", 
            "Whether to replace an existing load balance (if one exists with this name), or fail if one already exists",
            false);
    
    BasicAttributeSensorAndConfigKey<String> LOAD_BALANCER_NAME = new BasicAttributeSensorAndConfigKey<String>(
            String.class, 
            "aws.elb.loadBalancerName", 
            "The ELB name", 
            null);
    
    ConfigKey<Integer> LOAD_BALANCER_PORT = ConfigKeys.newIntegerConfigKey("aws.elb.loadBalancerPort", "The ELB port", 80);
    
    ConfigKey<String> LOAD_BALANCER_PROTOCOL = ConfigKeys.newStringConfigKey(
            "aws.elb.loadBalancerProtocol", "The load-balancer transport protocol to use for routing (HTTP, HTTPS, TCP, or SSL)", "HTTP");

    ConfigKey<String> SSL_CERTIFICATE_ID = ConfigKeys.newStringConfigKey(
            "aws.elb.sslCertificateId", "The ARN string of the server certificate", null);
    
    ConfigKey<Integer> INSTANCE_PORT = ConfigKeys.newIntegerConfigKey("aws.elb.instancePort", "The port for instances being balanced", 8080);

    ConfigKey<String> INSTANCE_PROTOCOL = ConfigKeys.newStringConfigKey(
            "aws.elb.instanceProtocol", "The protocol for routing traffic to back-end instances (HTTP, HTTPS, TCP, or SSL)", "HTTP");

    @SuppressWarnings("serial")
    ConfigKey<Collection<String>> AVAILABILITY_ZONES = ConfigKeys.newConfigKey(
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

    @SuppressWarnings("serial")
    BasicAttributeSensorAndConfigKey<Collection<String>> LOAD_BALANCER_SECURITY_GROUPS = new BasicAttributeSensorAndConfigKey<>(
            new TypeToken<Collection<String>>() {}, 
            "aws.elb.loadBalancerSecurityGroups", 
            "The security groups assigned to your LoadBalancer within your VPC");
    
    BasicAttributeSensorAndConfigKey<Collection<String>> LOAD_BALANCER_SUBNETS = new BasicAttributeSensorAndConfigKey<>(
            new TypeToken<Collection<String>>() {},
            "aws.elb.loadBalancerSubnets", 
            "A list of subnet IDs in your VPC to attach to your LoadBalancer");

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

    AttributeSensor<String> CANONICAL_HOSTED_ZONE_NAME = Sensors.newStringSensor(
            "aws.elb.canonicalHostedZoneName",
            "The hosted zone name of the ELB");

    AttributeSensor<String> CANONICAL_HOSTED_ZONE_ID = Sensors.newStringSensor(
            "aws.elb.canonicalHostedZoneId",
            "The hosted zone ID of the ELB");

    AttributeSensor<String> VPC_ID = Sensors.newStringSensor(
            "aws.elb.vpcId",
            "The id of the VPC the ELB is attached to");

    @Effector(description="Deletes the ELB")
    void deleteLoadBalancer();
}
