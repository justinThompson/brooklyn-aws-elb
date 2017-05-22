Brooklyn support for ELB
------------------------

Gives support for AWS's Elastic Load Balancer.

Note: this will likely be rolled into core https://github.com/brooklyncentral/brooklyn.
However, it currently depends on the 10MB aws-sdk so this dependency has deliberately not 
been added to core brooklyn.


## Build

To build, run `mvn clean install`.


## Releases

| Branch  | Version        | Apache Brooklyn version |
| --------|----------------|-------------------------|
| 0.1.x   | 0.1.0-SNAPSHOT | 0.6.0                   |
| 0.2.x   | 0.2.0-SNAPSHOT | 0.7.0-incubating        |
| 0.3.x   | 0.3.0-SNAPSHOT | 0.8.0-incubating        |
| 0.4.x   | 0.4.0-SNAPSHOT | 0.9.0                   |
| 0.5.x   | 0.5.0-SNAPSHOT | 0.10.0                  |
| master  | 0.6.0-SNAPSHOT | 0.11.0-SNAPSHOT         |



## Installation AMP

Start AMP and add the feature
    
    # Start Brooklyn/AMP karaf
    ${BROOKLYN_HOME}/bin/karaf
    
    # Add io.cloudsoft.aws.elb feature repo
    feature:repo-add mvn:io.cloudsoft.aws.elb/feature/0.6.0-SNAPSHOT/xml/features
    
    # Add the feature
    feature:install amp-aws-elb

## Installation Brooklyn

Start Brooklyn and add the feature
        
    # Update the setenv file
    
    vim ${BROOKLYN_HOME}/bin/setenv
    
    # Add io.cloudsoft domain to ClassLoaderUtils whitelist so that io.cloudsoft bundles are also scanned for classes.
    WHITELIST='org.apache.brooklyn.*|io.brooklyn.*|io.cloudsoft.*'
    export EXTRA_JAVA_OPTS="-Dorg.apache.brooklyn.classloader.fallback.bundles=${WHITELIST} ${EXTRA_JAVA_OPTS}"
    
    # Start Brooklyn/AMP karaf
    ${BROOKLYN_HOME}/bin/karaf
    
    # Add io.cloudsoft.aws.elb feature repo
    feature:repo-add mvn:io.cloudsoft.aws.elb/feature/0.6.0-SNAPSHOT/xml/features
    
    # Add the feature
    feature:install amp-aws-elb


## Example 

The example below creates an ELB, and cluster of Tomcat servers:

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
            type: org.apache.brooklyn.entity.webapp.tomcat.Tomcat8Server
            brooklyn.config:
              # points at maven-central 0.7.0-incubating/brooklyn-example-hello-world-webapp-0.7.0-incubating.war
              wars.root: https://bit.ly/brooklyn-0_7-helloworld-war
              http.port: 8080
      location: aws-ec2:us-east-1b

----

© 2013 Cloudsoft Corporation Limited. All rights reserved.

Use of this software is subject to the Cloudsoft EULA, provided in LICENSE.md and at 

http://www.cloudsoftcorp.com/cloudsoft-developer-license

Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations under the License.
