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


## Example

First add the required jars to your Apache Brooklyn release (see "Future Work" for discussion 
of OSGi): 

    BROOKLYN_HOME=~/repos/apache/brooklyn/brooklyn-dist/dist/target/brooklyn-dist/brooklyn/
    BROOKLYN_AWS_ELB_REPO=~/repos/cloudsoft/brooklyn-aws-elb
    MAVEN_REPO=~/.m2/repository

    AWS_SDK_VERSION=1.10.53
    BROOKLYN_AWS_ELB_VERSION=0.5.0-SNAPSHOT
    
    cp ${BROOKLYN_AWS_ELB_REPO}/target/brooklyn-aws-elb-${BROOKLYN_AWS_ELB_VERSION}.jar ${BROOKLYN_HOME}/lib/dropins/
    cp ${MAVEN_REPO}/com/amazonaws/aws-java-sdk*/${AWS_SDK_VERSION}/*.jar ${BROOKLYN_HOME}/lib/dropins/

And launch Brooklyn:

    ${BROOKLYN_HOME}/bin/brooklyn launch

Then deploy an app. The example below creates an ELB, and cluter of Tomcat servers:

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


## Future Work

This module should be built as an OSGi bundle, so that it can more easily be added to Brooklyn.

----

© 2013 Cloudsoft Corporation Limited. All rights reserved.

Use of this software is subject to the Cloudsoft EULA, provided in LICENSE.md and at 

http://www.cloudsoftcorp.com/cloudsoft-developer-license

Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations under the License.
