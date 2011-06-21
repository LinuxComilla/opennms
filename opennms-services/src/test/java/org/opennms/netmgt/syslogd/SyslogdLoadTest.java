// This file is part of the OpenNMS(R) Application.
//
// OpenNMS(R) is Copyright (C) 2010 The OpenNMS Group, Inc. All rights
// reserved.
// OpenNMS(R) is a derivative work, containing both original code, included
// code and modified
// code that was published under the GNU General Public License. Copyrights
// for modified
// and included code are below.
//
// OpenNMS(R) is a registered trademark of The OpenNMS Group, Inc.
//
// Modifications:
//
// 2010 Aug 19: Create from SyslogdTest  - jeffg@opennms.org
//
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; either version 2 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
//
// For more information contact:
//      OpenNMS Licensing <license@opennms.org>
//      http://www.opennms.org/
//      http://www.opennms.com/
//
package org.opennms.netmgt.syslogd;

import static org.junit.Assert.assertEquals;
import static org.opennms.core.utils.InetAddressUtils.addr;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.UndeclaredThrowableException;
import java.net.BindException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.io.IOUtils;
import org.apache.log4j.Level;
import org.exolab.castor.xml.MarshalException;
import org.exolab.castor.xml.ValidationException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.opennms.core.utils.InetAddressUtils;
import org.opennms.core.utils.LogUtils;
import org.opennms.netmgt.config.SyslogdConfigFactory;
import org.opennms.netmgt.config.syslogd.HideMessage;
import org.opennms.netmgt.config.syslogd.Match;
import org.opennms.netmgt.config.syslogd.UeiList;
import org.opennms.netmgt.config.syslogd.UeiMatch;
import org.opennms.netmgt.dao.db.JUnitConfigurationEnvironment;
import org.opennms.netmgt.dao.db.JUnitTemporaryDatabase;
import org.opennms.netmgt.dao.db.OpenNMSJUnit4ClassRunner;
import org.opennms.netmgt.eventd.Eventd;
import org.opennms.netmgt.mock.MockEventIpcManager;
import org.opennms.netmgt.model.events.EventBuilder;
import org.opennms.netmgt.model.events.EventListener;
import org.opennms.netmgt.model.events.EventProxy;
import org.opennms.netmgt.utils.TcpEventProxy;
import org.opennms.netmgt.xml.event.Event;
import org.opennms.netmgt.xml.event.Events;
import org.opennms.netmgt.xml.event.Log;
import org.opennms.test.ConfigurationTestUtils;
import org.opennms.test.mock.MockLogAppender;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Transactional;

@RunWith(OpenNMSJUnit4ClassRunner.class)
@ContextConfiguration(locations={
        "classpath:/META-INF/opennms/applicationContext-dao.xml",
        "classpath:/META-INF/opennms/applicationContext-commonConfigs.xml",
        "classpath:/META-INF/opennms/applicationContext-daemon.xml",
        "classpath:/META-INF/opennms/applicationContext-setupIpLike-enabled.xml",
        "classpath:/META-INF/opennms/mockEventIpcManager.xml",
        "classpath:/META-INF/opennms/applicationContext-databasePopulator.xml",
        "classpath:/META-INF/opennms/applicationContext-eventDaemon.xml",
        "classpath:/syslogdTest.xml"
})
@JUnitConfigurationEnvironment
@JUnitTemporaryDatabase
public class SyslogdLoadTest {

    private EventCounter m_eventCounter;
    private final String m_matchPattern = "^.*\\s(19|20)\\d\\d([-/.])(0[1-9]|1[012])\\2(0[1-9]|[12][0-9]|3[01])(\\s+)(\\S+)(\\s)(\\S.+)";
    private final int m_hostGroup = 6;
    private final int m_messageGroup = 8;
    private HideMessage m_HideMessages = new HideMessage();
    private String m_discardUei = "DISCARD-MATCHING-MESSAGES";
    private UeiList m_UeiList = new UeiList();

    @Autowired
    private MockEventIpcManager m_eventIpcManager;

    @Autowired
    private Eventd m_eventd;
    
    private Syslogd m_syslogd;

    public SyslogdLoadTest() {
        UeiMatch ueiMatch;
        Match match;
        for (int i = 0; i < 10000; i++) {
            /* <ueiMatch>
             *   <match type="regex" expression=".*foo0: .*load test (\S+) on ((pts\/\d+)|(tty\d+)).*"/><uei>uei.example.org/syslog/loadTest/foo0</uei>
             * </ueiMatch> */

            ueiMatch = new UeiMatch();
            match = new Match();
            match.setType("regex");
            match.setExpression(String.format(".*foo%d: .*load test (\\S+) on ((pts\\/\\d+)|(tty\\d+)).*", i));
            ueiMatch.setMatch(match);
            ueiMatch.setUei(String.format("uei.example.org/syslog/loadTest/foo%d", i));
            m_UeiList.addUeiMatch(ueiMatch);
        }
    }

    @Before
    public void setUp() throws Exception {
    	MockLogAppender.setupLogging(true, "WARN");

        loadSyslogConfiguration("/etc/syslogd-loadtest-configuration.xml");

        m_eventCounter = new EventCounter();
        this.m_eventIpcManager.addEventListener(m_eventCounter);
    }

    @After
    public void tearDown() throws Exception {
        if (m_syslogd != null) {
            m_syslogd.stop();
        }
        MockLogAppender.assertNotGreaterOrEqual(Level.FATAL);
    }

    private void loadSyslogConfiguration(final String configuration) throws IOException, MarshalException, ValidationException {
        InputStream stream = null;
        try {
            stream = ConfigurationTestUtils.getInputStreamForResource(this, configuration);
            SyslogdConfigFactory cf = new SyslogdConfigFactory(stream);
            SyslogdConfigFactory.setInstance(cf);
        } finally {
            if (stream != null) {
                IOUtils.closeQuietly(stream);
            }
        }
    }

    private void startSyslogdGracefully() {
        ConvertToEvent.invalidate();
        try {
            m_syslogd = new Syslogd();
            m_syslogd.init();
            m_syslogd.start();
        } catch (UndeclaredThrowableException ute) {
            if (ute.getCause() instanceof BindException) {
                LogUtils.warnf(this, ute, "received a bind exception");
                // continue, this was expected
            } else {
                throw ute;
            }
        }
    }

    @Test
    @Transactional
    public void testDefaultSyslogd() throws Exception {
        startSyslogdGracefully();

        int eventCount = 100;
        
        List<Integer> foos = new ArrayList<Integer>();

        for (int i = 0; i < eventCount; i++) {
            int eventNum = Double.valueOf(Math.random() * 10000).intValue();
            foos.add(eventNum);
        }

        m_eventCounter.setAnticipated(eventCount);

        long start = System.currentTimeMillis();
        String testPduFormat = "2010-08-19 localhost foo%d: load test %d on tty1";
        SyslogClient sc = new SyslogClient(null, 10, SyslogClient.LOG_DEBUG);
        for (int i = 0; i < eventCount; i++) {
            int foo = foos.get(i);
            DatagramPacket pkt = sc.getPacket(SyslogClient.LOG_DEBUG, String.format(testPduFormat, foo, foo));
            Thread worker = new Thread(new SyslogConnection(pkt, m_matchPattern, m_hostGroup, m_messageGroup, m_UeiList, m_HideMessages, m_discardUei), SyslogConnection.class.getSimpleName());
            worker.start();
        }

        long mid = System.currentTimeMillis();
        m_eventCounter.waitForFinish(120000);
        long end = System.currentTimeMillis();
        
        final long total = (end - start);
        final double eventsPerSecond = (eventCount * 1000.0 / total);
        System.err.println(String.format("total time: %d, wait time: %d, events per second: %8.4f", total, (end - mid), eventsPerSecond));
    }

    @Test
    @Transactional
    public void testRfcSyslog() throws Exception {
        loadSyslogConfiguration("/etc/syslogd-rfc-configuration.xml");

        startSyslogdGracefully();

        m_eventCounter.anticipate();

        InetAddress address = InetAddress.getLocalHost();

        // handle an invalid packet
        byte[] bytes = "<34>1 2010-08-19T22:14:15.000Z localhost - - - - BOMfoo0: load test 0 on tty1\0".getBytes();
        DatagramPacket pkt = new DatagramPacket(bytes, bytes.length, address, SyslogClient.PORT);
        Thread worker = new Thread(new SyslogConnection(pkt, m_matchPattern, m_hostGroup, m_messageGroup, m_UeiList, m_HideMessages, m_discardUei), SyslogConnection.class.getSimpleName());
        worker.run();

        // handle a valid packet
        bytes = "<34>1 2003-10-11T22:14:15.000Z plonk -ev/pts/8\0".getBytes();
        pkt = new DatagramPacket(bytes, bytes.length, address, SyslogClient.PORT);
        worker = new Thread(new SyslogConnection(pkt, m_matchPattern, m_hostGroup, m_messageGroup, m_UeiList, m_HideMessages, m_discardUei), SyslogConnection.class.getSimpleName());
        worker.run();

        m_eventCounter.waitForFinish(120000);
        
        assertEquals(1, m_eventCounter.getCount());
    }

    @Test
    @Transactional
    public void testNGSyslog() throws Exception {
        loadSyslogConfiguration("/etc/syslogd-syslogng-configuration.xml");

        startSyslogdGracefully();

        m_eventCounter.anticipate();

        InetAddress address = InetAddress.getLocalHost();

        // handle an invalid packet
        byte[] bytes = "<34>main: 2010-08-19 localhost foo0: load test 0 on tty1\0".getBytes();
        DatagramPacket pkt = new DatagramPacket(bytes, bytes.length, address, SyslogClient.PORT);
        Thread worker = new Thread(new SyslogConnection(pkt, m_matchPattern, m_hostGroup, m_messageGroup, m_UeiList, m_HideMessages, m_discardUei), SyslogConnection.class.getSimpleName());
        worker.run();

        // handle a valid packet
        bytes = "<34>monkeysatemybrain!\0".getBytes();
        pkt = new DatagramPacket(bytes, bytes.length, address, SyslogClient.PORT);
        worker = new Thread(new SyslogConnection(pkt, m_matchPattern, m_hostGroup, m_messageGroup, m_UeiList, m_HideMessages, m_discardUei), SyslogConnection.class.getSimpleName());
        worker.run();

        m_eventCounter.waitForFinish(120000);
        
        assertEquals(1, m_eventCounter.getCount());
    }

    @Test
    @Transactional
    public void testEventd() throws Exception {
    	m_eventd.start();

        EventProxy ep = this.createEventProxy();

        Log eventLog = new Log();
        Events events = new Events();
        eventLog.setEvents(events);
        
        int eventCount = 10000;
        m_eventCounter.setAnticipated(eventCount);

        for (int i = 0; i < eventCount; i++) {
            int eventNum = Double.valueOf(Math.random() * 300).intValue();
            String expectedUei = "uei.example.org/syslog/loadTest/foo" + eventNum;
            final EventBuilder eb = new EventBuilder(expectedUei, "SyslogdLoadTest");

            Event thisEvent = eb.setInterface(addr("127.0.0.1"))
                .setLogDest("logndisplay")
                .setLogMessage("A load test has been received as a Syslog Message")
                .getEvent();
//            LogUtils.debugf(this, "event = %s", thisEvent);
            events.addEvent(thisEvent);
        }

        long start = System.currentTimeMillis();
        ep.send(eventLog);
        long mid = System.currentTimeMillis();
        // wait up to 2 minutes for the events to come through
        m_eventCounter.waitForFinish(120000);
        long end = System.currentTimeMillis();

        m_eventd.stop();

        final long total = (end - start);
        final double eventsPerSecond = (eventCount * 1000.0 / total);
        System.err.println(String.format("total time: %d, wait time: %d, events per second: %8.4f", total, (end - mid), eventsPerSecond));
    }

    private EventProxy createEventProxy() throws UnknownHostException {
        /*
         * Rather than defaulting to localhost all the time, give an option in properties
         */
        String proxyHostName = "127.0.0.1";
        String proxyHostPort = "5837";
        String proxyHostTimeout = String.valueOf(TcpEventProxy.DEFAULT_TIMEOUT);
        InetAddress proxyAddr = null;
        EventProxy proxy = null;

        proxyAddr = InetAddressUtils.addr(proxyHostName);

        if (proxyAddr == null) {
        	proxy = new TcpEventProxy();
        } else {
            proxy = new TcpEventProxy(new InetSocketAddress(proxyAddr, Integer.parseInt(proxyHostPort)), Integer.parseInt(proxyHostTimeout));
        }
        return proxy;
    }

    public class EventCounter implements EventListener {
        private AtomicInteger m_eventCount = new AtomicInteger(0);
        private int m_expectedCount = 0;

        public String getName() {
            return "eventCounter";
        }

        // Me love you, long time.
        public void waitForFinish(final long time) {
            final long start = System.currentTimeMillis();
            while (m_eventCounter.getCount() < m_expectedCount) {
                if (System.currentTimeMillis() - start > time) {
                    LogUtils.warnf(this, "waitForFinish timeout (%s) reached", time);
                    break;
                }
                try {
                    Thread.sleep(50);
                } catch (final InterruptedException e) {
                    LogUtils.warnf(this, e, "thread was interrupted while sleeping");
                    Thread.currentThread().interrupt();
                }
            }
        }

        public void setAnticipated(final int eventCount) {
            m_expectedCount = eventCount;
        }

        public int getCount() {
            return m_eventCount.get();
        }

        public void anticipate() {
            m_expectedCount++;
        }

        public void onEvent(final Event e) {
            final int current = m_eventCount.incrementAndGet();
            if (current % 100 == 0) {
                System.err.println(current + " < " + m_expectedCount);
            }
        }

    }
}
