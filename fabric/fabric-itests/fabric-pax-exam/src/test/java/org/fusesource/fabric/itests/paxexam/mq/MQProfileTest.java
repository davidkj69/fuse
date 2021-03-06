package org.fusesource.fabric.itests.paxexam.mq;

import org.apache.activemq.broker.jmx.BrokerViewMBean;
import org.fusesource.fabric.api.Container;
import org.fusesource.fabric.itests.paxexam.FabricTestSupport;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.ops4j.pax.exam.MavenUtils;
import org.ops4j.pax.exam.Option;
import org.ops4j.pax.exam.junit.Configuration;
import org.ops4j.pax.exam.junit.ExamReactorStrategy;
import org.ops4j.pax.exam.junit.JUnit4TestRunner;
import org.ops4j.pax.exam.options.DefaultCompositeOption;
import org.ops4j.pax.exam.spi.reactors.AllConfinedStagedReactorFactory;

import javax.management.ObjectName;
import java.util.ArrayList;

import static junit.framework.Assert.*;

@RunWith(JUnit4TestRunner.class)
@ExamReactorStrategy(AllConfinedStagedReactorFactory.class)
public class MQProfileTest extends FabricTestSupport {
    
    ArrayList<Container> containers = new ArrayList<Container>();

    @After
    public void tearDown() throws InterruptedException {
       for (Container container : containers) {
           System.out.println("destroying " + container.getId());
           destroyChildContainer(container.getId());
       }
    }

    @Test
    public void testLocalChildCreation() throws Exception {
        System.err.println(executeCommand("fabric:create"));
        addStagingRepoToDefaultProfile();
        containers.add(createAndAssertChildContainer("mq1", "root", "mq"));

        containers.add(createAndAssertChildContainer("example", "root", "example-mq"));


        // give it a bit time
        Thread.sleep(3000);

        // check jmx stats
        Container container = getFabricService().getContainer("mq1");
        BrokerViewMBean bean = (BrokerViewMBean)getMBean(container, new ObjectName("org.apache.activemq:Type=Broker,BrokerName=mq1"), BrokerViewMBean.class);
        assertEquals("Producer not present", 1, bean.getTotalProducerCount());
        assertEquals("Consumer not present", 1, bean.getTotalConsumerCount());
    }
    
    @Test
    public void testMQCreateBasic() throws Exception {
        System.err.println(executeCommand("fabric:create"));
        addStagingRepoToDefaultProfile();

        executeCommand("mq-create --create-container mq1 mq1");
        Container container = getFabricService().getContainer("mq1");
        containers.add(container);
        waitForProvisionSuccess(container, PROVISION_TIMEOUT);


        containers.add(createAndAssertChildContainer("example", "root", "example-mq"));

        // give it a bit time
        Thread.sleep(3000);

        // check jmx stats
        BrokerViewMBean bean = (BrokerViewMBean)getMBean(container, new ObjectName("org.apache.activemq:Type=Broker,BrokerName=mq1"), BrokerViewMBean.class);
        assertEquals("Producer not present", 1, bean.getTotalProducerCount());
        assertEquals("Consumer not present", 1, bean.getTotalConsumerCount());
    }

    @Test
    public void testMQCreateMS() throws Exception {
        System.err.println(executeCommand("fabric:create"));
        addStagingRepoToDefaultProfile();

        executeCommand("mq-create --create-container broker1,broker2 ms-broker");
        Container container1 = getFabricService().getContainer("broker1");
        containers.add(container1);
        waitForProvisionSuccess(container1, PROVISION_TIMEOUT);

        Container container2 = getFabricService().getContainer("broker2");
        containers.add(container2);
        waitForProvisionSuccess(container2, PROVISION_TIMEOUT);

        BrokerViewMBean broker1 = (BrokerViewMBean)getMBean(container1, new ObjectName("org.apache.activemq:Type=Broker,BrokerName=ms-broker"), BrokerViewMBean.class);

        assertEquals("ms-broker", broker1.getBrokerName());
        destroyChildContainer("broker1");
        containers.remove(container1);
        Thread.sleep(10000); // TODO implement wait for condition with timeout

        BrokerViewMBean broker2 = (BrokerViewMBean)getMBean(container2, new ObjectName("org.apache.activemq:Type=Broker,BrokerName=ms-broker"), BrokerViewMBean.class);
        assertEquals("ms-broker", broker2.getBrokerName());

        //TODO add example to verify failover

    }
    
    @Test
    public void testMQCreateNetwork() throws Exception {
        System.err.println(executeCommand("fabric:create"));
        addStagingRepoToDefaultProfile();

        executeCommand("mq-create --group east --networks west --create-container east east");
        Container container1 = getFabricService().getContainer("east");
        containers.add(container1);
        waitForProvisionSuccess(container1, PROVISION_TIMEOUT);


        executeCommand("mq-create --group west --networks east --create-container west west");
        Container container2 = getFabricService().getContainer("west");
        containers.add(container2);
        waitForProvisionSuccess(container2, PROVISION_TIMEOUT);

        containers.add(createAndAssertChildContainer("example", "root", "example-mq-cluster"));

        // give it a bit time
        Thread.sleep(10000);

        BrokerViewMBean brokerEast = (BrokerViewMBean)getMBean(container1, new ObjectName("org.apache.activemq:Type=Broker,BrokerName=east"), BrokerViewMBean.class);
        BrokerViewMBean brokerWest = (BrokerViewMBean)getMBean(container2, new ObjectName("org.apache.activemq:Type=Broker,BrokerName=west"), BrokerViewMBean.class);

        assertFalse("Messages not sent", brokerEast.getTotalEnqueueCount() == 0);

        assertFalse("Messages not received", brokerWest.getTotalDequeueCount() == 0);





    }

    @Configuration
    public Option[] config() {
        return new Option[] {new DefaultCompositeOption(fabricDistributionConfiguration()),
               mavenBundle("org.apache.activemq", "activemq-all", MavenUtils.getArtifactVersion("org.apache.activemq", "activemq-core"))};
    }
}
