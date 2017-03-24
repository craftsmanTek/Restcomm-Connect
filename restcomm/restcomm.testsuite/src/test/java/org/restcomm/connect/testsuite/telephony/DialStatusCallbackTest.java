/*
 * TeleStax, Open Source Cloud Communications
 * Copyright 2011-2014, Telestax Inc and individual contributors
 * by the @authors tag.
 *
 * This program is free software: you can redistribute it and/or modify
 * under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation; either version 3 of
 * the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 *
 */

package org.restcomm.connect.testsuite.telephony;

import com.github.tomakehurst.wiremock.junit.WireMockRule;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.google.gson.JsonObject;
import org.apache.log4j.Logger;
import org.cafesip.sipunit.SipCall;
import org.cafesip.sipunit.SipPhone;
import org.cafesip.sipunit.SipStack;
import org.cafesip.sipunit.SipTransaction;
import org.jboss.arquillian.container.mss.extension.SipStackTool;
import org.jboss.arquillian.container.test.api.Deployer;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.jboss.shrinkwrap.resolver.api.maven.archive.ShrinkWrapMaven;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.restcomm.connect.commons.Version;
import org.restcomm.connect.testsuite.http.RestcommCallsTool;
import org.restcomm.connect.testsuite.tools.MonitoringServiceTool;

import javax.sip.address.SipURI;
import javax.sip.header.Header;
import javax.sip.message.Response;
import java.net.URL;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.findAll;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.cafesip.sipunit.SipAssert.assertLastOperationSuccess;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Test for Dial status callback attribute. Reference: The 'statuscallback'
 * attribute takes a URL as an argument. As the call moves states, Restcomm will make a GET or POST request to this URL
 *
 * @author <a href="mailto:gvagenas@gmail.com">gvagenas</a>
 *
 */
@RunWith(Arquillian.class)
public class DialStatusCallbackTest {

    private final static Logger logger = Logger.getLogger(DialStatusCallbackTest.class.getName());

    private static final String version = Version.getVersion();
    private static final byte[] bytes = new byte[] { 118, 61, 48, 13, 10, 111, 61, 117, 115, 101, 114, 49, 32, 53, 51, 54, 53,
        53, 55, 54, 53, 32, 50, 51, 53, 51, 54, 56, 55, 54, 51, 55, 32, 73, 78, 32, 73, 80, 52, 32, 49, 50, 55, 46, 48, 46,
        48, 46, 49, 13, 10, 115, 61, 45, 13, 10, 99, 61, 73, 78, 32, 73, 80, 52, 32, 49, 50, 55, 46, 48, 46, 48, 46, 49,
        13, 10, 116, 61, 48, 32, 48, 13, 10, 109, 61, 97, 117, 100, 105, 111, 32, 54, 48, 48, 48, 32, 82, 84, 80, 47, 65,
        86, 80, 32, 48, 13, 10, 97, 61, 114, 116, 112, 109, 97, 112, 58, 48, 32, 80, 67, 77, 85, 47, 56, 48, 48, 48, 13, 10 };
    private static final String body = new String(bytes);

    @ArquillianResource
    private Deployer deployer;
    @ArquillianResource
    URL deploymentUrl;

    //Dial Action URL: http://ACae6e420f425248d6a26948c17a9e2acf:77f8c12cc7b8f8423e5c38b035249166@127.0.0.1:8080/restcomm/2012-04-24/DialAction Method: POST
    @Rule
    public WireMockRule wireMockRule = new WireMockRule(8090); // No-args constructor defaults to port 8080

    private static SipStackTool tool1;
    private static SipStackTool tool2;
    private static SipStackTool tool3;
    private static SipStackTool tool4;

    // Bob is a simple SIP Client. Will not register with Restcomm
    private SipStack bobSipStack;
    private SipPhone bobPhone;
    private String bobContact = "sip:bob@127.0.0.1:5090";

    // Alice is a Restcomm Client with VoiceURL. This Restcomm Client can register with Restcomm and whatever will dial the RCML
    // of the VoiceURL will be executed.
    private SipStack aliceSipStack;
    private SipPhone alicePhone;
    private String aliceContact = "sip:alice@127.0.0.1:5091";

    // Henrique is a simple SIP Client. Will not register with Restcomm
    private SipStack henriqueSipStack;
    private SipPhone henriquePhone;
    private String henriqueContact = "sip:henrique@127.0.0.1:5092";

    // George is a simple SIP Client. Will not register with Restcomm
    private SipStack georgeSipStack;
    private SipPhone georgePhone;
    private String georgeContact = "sip:+131313@127.0.0.1:5070";

    private String dialRestcomm = "sip:1111@127.0.0.1:5080"; // Application: dial-client-entry_wActionUrl.xml

    private String adminAccountSid = "ACae6e420f425248d6a26948c17a9e2acf";
    private String adminAuthToken = "77f8c12cc7b8f8423e5c38b035249166";

    @BeforeClass
    public static void beforeClass() throws Exception {
        tool1 = new SipStackTool("DialActionTest1");
        tool2 = new SipStackTool("DialActionTest2");
        tool3 = new SipStackTool("DialActionTest3");
        tool4 = new SipStackTool("DialActionTest4");
    }

    @Before
    public void before() throws Exception {
        bobSipStack = tool1.initializeSipStack(SipStack.PROTOCOL_UDP, "127.0.0.1", "5090", "127.0.0.1:5080");
        bobPhone = bobSipStack.createSipPhone("127.0.0.1", SipStack.PROTOCOL_UDP, 5080, bobContact);

        aliceSipStack = tool2.initializeSipStack(SipStack.PROTOCOL_UDP, "127.0.0.1", "5091", "127.0.0.1:5080");
        alicePhone = aliceSipStack.createSipPhone("127.0.0.1", SipStack.PROTOCOL_UDP, 5080, aliceContact);

        henriqueSipStack = tool3.initializeSipStack(SipStack.PROTOCOL_UDP, "127.0.0.1", "5092", "127.0.0.1:5080");
        henriquePhone = henriqueSipStack.createSipPhone("127.0.0.1", SipStack.PROTOCOL_UDP, 5080, henriqueContact);

        georgeSipStack = tool4.initializeSipStack(SipStack.PROTOCOL_UDP, "127.0.0.1", "5070", "127.0.0.1:5080");
        georgePhone = georgeSipStack.createSipPhone("127.0.0.1", SipStack.PROTOCOL_UDP, 5080, georgeContact);
    }

    @After
    public void after() throws Exception {
        if (bobPhone != null) {
            bobPhone.dispose();
        }
        if (bobSipStack != null) {
            bobSipStack.dispose();
        }

        if (aliceSipStack != null) {
            aliceSipStack.dispose();
        }
        if (alicePhone != null) {
            alicePhone.dispose();
        }

        if (henriqueSipStack != null) {
            henriqueSipStack.dispose();
        }
        if (henriquePhone != null) {
            henriquePhone.dispose();
        }

        if (georgePhone != null) {
            georgePhone.dispose();
        }
        if (georgeSipStack != null) {
            georgeSipStack.dispose();
        }
        Thread.sleep(1000);
        wireMockRule.resetRequests();
        Thread.sleep(4000);
    }

    private String dialStatusCallback = "<Response><Dial><Client statusCallback=\"http://127.0.0.1:8090/status\">alice</Client></Dial></Response>";
    @Test
    public void testDialStatusCallbackAliceDisconnects() throws ParseException, InterruptedException {

        stubFor(get(urlPathEqualTo("/1111"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/xml")
                        .withBody(dialStatusCallback)));

        stubFor(post(urlPathMatching("/status.*"))
                .willReturn(aResponse()
                    .withStatus(200)));

        // Register Alice
        SipURI uri = aliceSipStack.getAddressFactory().createSipURI(null, "127.0.0.1:5080");
        assertTrue(alicePhone.register(uri, "alice", "1234", aliceContact, 3600, 3600));

        // Prepare Alice to receive call
        final SipCall aliceCall = alicePhone.createSipCall();
        aliceCall.listenForIncomingCall();

        // Create outgoing call with first phone
        final SipCall bobCall = bobPhone.createSipCall();
        bobCall.initiateOutgoingCall(bobContact, dialRestcomm, null, body, "application", "sdp", null, null);
        assertLastOperationSuccess(bobCall);
        assertTrue(bobCall.waitOutgoingCallResponse(5 * 1000));
        final int response = bobCall.getLastReceivedResponse().getStatusCode();
        assertTrue(response == Response.TRYING || response == Response.RINGING);

        if (response == Response.TRYING) {
            assertTrue(bobCall.waitOutgoingCallResponse(5 * 1000));
            assertEquals(Response.RINGING, bobCall.getLastReceivedResponse().getStatusCode());
        }

        assertTrue(bobCall.waitOutgoingCallResponse(5 * 1000));
        assertEquals(Response.OK, bobCall.getLastReceivedResponse().getStatusCode());
        bobCall.sendInviteOkAck();

        assertTrue(aliceCall.waitForIncomingCall(5000));
        assertTrue(aliceCall.sendIncomingCallResponse(Response.TRYING, "Alice-Trying", 3600));
        assertTrue(aliceCall.sendIncomingCallResponse(Response.RINGING, "Alice-Ringing", 3600));
        String receivedBody = new String(aliceCall.getLastReceivedRequest().getRawContent());
        assertTrue(aliceCall.sendIncomingCallResponse(Response.OK, "Alice-OK", 3600, receivedBody, "application", "sdp",
                null, null));
        assertTrue(aliceCall.waitForAck(5000));

        Thread.sleep(1000);
        int liveCalls = MonitoringServiceTool.getInstance().getLiveCalls(deploymentUrl.toString(),adminAccountSid, adminAuthToken);
        int liveIncomingCalls = MonitoringServiceTool.getInstance().getLiveIncomingCalls(deploymentUrl.toString(),adminAccountSid, adminAuthToken);
        int liveOutgoingCalls = MonitoringServiceTool.getInstance().getLiveOutgoingCalls(deploymentUrl.toString(),adminAccountSid, adminAuthToken);
        int liveCallsArraySize = MonitoringServiceTool.getInstance().getLiveCallsArraySize(deploymentUrl.toString(),adminAccountSid, adminAuthToken);
        assertTrue(liveCalls==2);
        assertTrue(liveIncomingCalls==1);
        assertTrue(liveOutgoingCalls==1);
        assertTrue(liveCallsArraySize==2);


        Thread.sleep(3000);
        bobCall.listenForDisconnect();

        assertTrue(aliceCall.disconnect());
        Thread.sleep(500);
        assertTrue(bobCall.waitForDisconnect(5000));
        assertTrue(bobCall.respondToDisconnect());

        Thread.sleep(10000);

        logger.info("About to check the StatusCallback Requests");
        List<LoggedRequest> requests = findAll(postRequestedFor(urlPathMatching("/status.*")));
        assertEquals(4, requests.size());
        String requestBody = requests.get(0).getBodyAsString();
        assertTrue(requestBody.contains("SequenceNumber=0"));
        assertTrue(requestBody.contains("CallStatus=initiated"));

        requestBody = requests.get(1).getBodyAsString();
        assertTrue(requestBody.contains("SequenceNumber=1"));
        assertTrue(requestBody.contains("CallStatus=ringing"));

        requestBody = requests.get(2).getBodyAsString();
        assertTrue(requestBody.contains("SequenceNumber=2"));
        assertTrue(requestBody.contains("CallStatus=answered"));

        requestBody = requests.get(3).getBodyAsString();
        assertTrue(requestBody.contains("SequenceNumber=3"));
        assertTrue(requestBody.contains("CallStatus=completed"));

        liveCalls = MonitoringServiceTool.getInstance().getLiveCalls(deploymentUrl.toString(),adminAccountSid, adminAuthToken);
        liveIncomingCalls = MonitoringServiceTool.getInstance().getLiveIncomingCalls(deploymentUrl.toString(),adminAccountSid, adminAuthToken);
        liveOutgoingCalls = MonitoringServiceTool.getInstance().getLiveOutgoingCalls(deploymentUrl.toString(),adminAccountSid, adminAuthToken);
        liveCallsArraySize = MonitoringServiceTool.getInstance().getLiveCallsArraySize(deploymentUrl.toString(),adminAccountSid, adminAuthToken);
        assertTrue(liveCalls==0);
        assertTrue(liveIncomingCalls==0);
        assertTrue(liveOutgoingCalls==0);
        assertTrue(liveCallsArraySize==0);
    }

    private String dialStatusCallbackGetMethod = "<Response><Dial><Client statusCallback=\"http://127.0.0.1:8090/status\" " +
            "statusCallbackMethod=\"get\">alice</Client></Dial></Response>";
    @Test
    public void testDialStatusCallbackMethodGET() throws ParseException, InterruptedException {

        stubFor(get(urlPathEqualTo("/1111"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/xml")
                        .withBody(dialStatusCallbackGetMethod)));

        stubFor(get(urlPathMatching("/status.*"))
                .willReturn(aResponse()
                        .withStatus(200)));

        // Register Alice
        SipURI uri = aliceSipStack.getAddressFactory().createSipURI(null, "127.0.0.1:5080");
        assertTrue(alicePhone.register(uri, "alice", "1234", aliceContact, 3600, 3600));

        // Prepare Alice to receive call
        final SipCall aliceCall = alicePhone.createSipCall();
        aliceCall.listenForIncomingCall();

        // Create outgoing call with first phone
        final SipCall bobCall = bobPhone.createSipCall();
        bobCall.initiateOutgoingCall(bobContact, dialRestcomm, null, body, "application", "sdp", null, null);
        assertLastOperationSuccess(bobCall);
        assertTrue(bobCall.waitOutgoingCallResponse(5 * 1000));
        final int response = bobCall.getLastReceivedResponse().getStatusCode();
        assertTrue(response == Response.TRYING || response == Response.RINGING);

        if (response == Response.TRYING) {
            assertTrue(bobCall.waitOutgoingCallResponse(5 * 1000));
            assertEquals(Response.RINGING, bobCall.getLastReceivedResponse().getStatusCode());
        }

        assertTrue(bobCall.waitOutgoingCallResponse(5 * 1000));
        assertEquals(Response.OK, bobCall.getLastReceivedResponse().getStatusCode());
        bobCall.sendInviteOkAck();

        assertTrue(aliceCall.waitForIncomingCall(5000));
        assertTrue(aliceCall.sendIncomingCallResponse(Response.TRYING, "Alice-Trying", 3600));
        assertTrue(aliceCall.sendIncomingCallResponse(Response.RINGING, "Alice-Ringing", 3600));
        String receivedBody = new String(aliceCall.getLastReceivedRequest().getRawContent());
        assertTrue(aliceCall.sendIncomingCallResponse(Response.OK, "Alice-OK", 3600, receivedBody, "application", "sdp",
                null, null));
        assertTrue(aliceCall.waitForAck(5000));

        Thread.sleep(1000);
        int liveCalls = MonitoringServiceTool.getInstance().getLiveCalls(deploymentUrl.toString(),adminAccountSid, adminAuthToken);
        int liveIncomingCalls = MonitoringServiceTool.getInstance().getLiveIncomingCalls(deploymentUrl.toString(),adminAccountSid, adminAuthToken);
        int liveOutgoingCalls = MonitoringServiceTool.getInstance().getLiveOutgoingCalls(deploymentUrl.toString(),adminAccountSid, adminAuthToken);
        int liveCallsArraySize = MonitoringServiceTool.getInstance().getLiveCallsArraySize(deploymentUrl.toString(),adminAccountSid, adminAuthToken);
        assertTrue(liveCalls==2);
        assertTrue(liveIncomingCalls==1);
        assertTrue(liveOutgoingCalls==1);
        assertTrue(liveCallsArraySize==2);


        Thread.sleep(3000);
        bobCall.listenForDisconnect();

        assertTrue(aliceCall.disconnect());
        Thread.sleep(500);
        assertTrue(bobCall.waitForDisconnect(5000));
        assertTrue(bobCall.respondToDisconnect());

        Thread.sleep(10000);

        logger.info("About to check the StatusCallback Requests");
        List<LoggedRequest> requests = findAll(getRequestedFor(urlPathMatching("/status.*")));
        assertEquals(4, requests.size());

        String requestUrl = requests.get(0).getUrl();
        assertTrue(requestUrl.contains("SequenceNumber=0"));
        assertTrue(requestUrl.contains("CallStatus=initiated"));

        requestUrl = requests.get(1).getUrl();
        assertTrue(requestUrl.contains("SequenceNumber=1"));
        assertTrue(requestUrl.contains("CallStatus=ringing"));

        requestUrl = requests.get(2).getUrl();
        assertTrue(requestUrl.contains("SequenceNumber=2"));
        assertTrue(requestUrl.contains("CallStatus=answered"));

        requestUrl = requests.get(3).getUrl();
        assertTrue(requestUrl.contains("SequenceNumber=3"));
        assertTrue(requestUrl.contains("CallStatus=completed"));

        liveCalls = MonitoringServiceTool.getInstance().getLiveCalls(deploymentUrl.toString(),adminAccountSid, adminAuthToken);
        liveIncomingCalls = MonitoringServiceTool.getInstance().getLiveIncomingCalls(deploymentUrl.toString(),adminAccountSid, adminAuthToken);
        liveOutgoingCalls = MonitoringServiceTool.getInstance().getLiveOutgoingCalls(deploymentUrl.toString(),adminAccountSid, adminAuthToken);
        liveCallsArraySize = MonitoringServiceTool.getInstance().getLiveCallsArraySize(deploymentUrl.toString(),adminAccountSid, adminAuthToken);
        assertTrue(liveCalls==0);
        assertTrue(liveIncomingCalls==0);
        assertTrue(liveOutgoingCalls==0);
        assertTrue(liveCallsArraySize==0);
    }

    @Test
    public void testDialStatusCallbackBobDisconnects() throws ParseException, InterruptedException {

        stubFor(get(urlPathEqualTo("/1111"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/xml")
                        .withBody(dialStatusCallback)));

        stubFor(post(urlPathMatching("/status.*"))
                .willReturn(aResponse()
                        .withStatus(200)));

        // Register Alice
        SipURI uri = aliceSipStack.getAddressFactory().createSipURI(null, "127.0.0.1:5080");
        assertTrue(alicePhone.register(uri, "alice", "1234", aliceContact, 3600, 3600));

        // Prepare Alice to receive call
        final SipCall aliceCall = alicePhone.createSipCall();
        aliceCall.listenForIncomingCall();

        // Create outgoing call with first phone
        final SipCall bobCall = bobPhone.createSipCall();
        bobCall.initiateOutgoingCall(bobContact, dialRestcomm, null, body, "application", "sdp", null, null);
        assertLastOperationSuccess(bobCall);
        assertTrue(bobCall.waitOutgoingCallResponse(5 * 1000));
        final int response = bobCall.getLastReceivedResponse().getStatusCode();
        assertTrue(response == Response.TRYING || response == Response.RINGING);

        if (response == Response.TRYING) {
            assertTrue(bobCall.waitOutgoingCallResponse(5 * 1000));
            assertEquals(Response.RINGING, bobCall.getLastReceivedResponse().getStatusCode());
        }

        assertTrue(bobCall.waitOutgoingCallResponse(5 * 1000));
        assertEquals(Response.OK, bobCall.getLastReceivedResponse().getStatusCode());
        bobCall.sendInviteOkAck();

        assertTrue(aliceCall.waitForIncomingCall(5000));
        assertTrue(aliceCall.sendIncomingCallResponse(Response.TRYING, "Alice-Trying", 3600));
        assertTrue(aliceCall.sendIncomingCallResponse(Response.RINGING, "Alice-Ringing", 3600));
        String receivedBody = new String(aliceCall.getLastReceivedRequest().getRawContent());
        assertTrue(aliceCall.sendIncomingCallResponse(Response.OK, "Alice-OK", 3600, receivedBody, "application", "sdp",
                null, null));
        assertTrue(aliceCall.waitForAck(5000));

        Thread.sleep(1000);
        int liveCalls = MonitoringServiceTool.getInstance().getLiveCalls(deploymentUrl.toString(),adminAccountSid, adminAuthToken);
        int liveIncomingCalls = MonitoringServiceTool.getInstance().getLiveIncomingCalls(deploymentUrl.toString(),adminAccountSid, adminAuthToken);
        int liveOutgoingCalls = MonitoringServiceTool.getInstance().getLiveOutgoingCalls(deploymentUrl.toString(),adminAccountSid, adminAuthToken);
        int liveCallsArraySize = MonitoringServiceTool.getInstance().getLiveCallsArraySize(deploymentUrl.toString(),adminAccountSid, adminAuthToken);
        assertTrue(liveCalls==2);
        assertTrue(liveIncomingCalls==1);
        assertTrue(liveOutgoingCalls==1);
        assertTrue(liveCallsArraySize==2);


        Thread.sleep(3000);
        aliceCall.listenForDisconnect();

        assertTrue(bobCall.disconnect());
        Thread.sleep(500);
        assertTrue(aliceCall.waitForDisconnect(5000));
        assertTrue(aliceCall.respondToDisconnect());

        Thread.sleep(10000);

        logger.info("About to check the StatusCallback Requests");
        List<LoggedRequest> requests = findAll(postRequestedFor(urlPathMatching("/status.*")));
        assertEquals(4, requests.size());
        String requestBody = requests.get(0).getBodyAsString();
        assertTrue(requestBody.contains("SequenceNumber=0"));
        assertTrue(requestBody.contains("CallStatus=initiated"));

        requestBody = requests.get(1).getBodyAsString();
        assertTrue(requestBody.contains("SequenceNumber=1"));
        assertTrue(requestBody.contains("CallStatus=ringing"));

        requestBody = requests.get(2).getBodyAsString();
        assertTrue(requestBody.contains("SequenceNumber=2"));
        assertTrue(requestBody.contains("CallStatus=answered"));

        requestBody = requests.get(3).getBodyAsString();
        assertTrue(requestBody.contains("SequenceNumber=3"));
        assertTrue(requestBody.contains("CallStatus=completed"));

        liveCalls = MonitoringServiceTool.getInstance().getLiveCalls(deploymentUrl.toString(),adminAccountSid, adminAuthToken);
        liveIncomingCalls = MonitoringServiceTool.getInstance().getLiveIncomingCalls(deploymentUrl.toString(),adminAccountSid, adminAuthToken);
        liveOutgoingCalls = MonitoringServiceTool.getInstance().getLiveOutgoingCalls(deploymentUrl.toString(),adminAccountSid, adminAuthToken);
        liveCallsArraySize = MonitoringServiceTool.getInstance().getLiveCallsArraySize(deploymentUrl.toString(),adminAccountSid, adminAuthToken);
        assertTrue(liveCalls==0);
        assertTrue(liveIncomingCalls==0);
        assertTrue(liveOutgoingCalls==0);
        assertTrue(liveCallsArraySize==0);
    }

    private String dialStatusCallbackOnlyInitiatedAndAnswer = "<Response><Dial><Client statusCallback=\"http://127.0.0.1:8090/status\" " +
            "statusCallbackEvent=\"initiated,  answered\">alice</Client></Dial></Response>";
    @Test
    public void testDialStatusCallbackOnlyInitiatedAnswerEvent() throws ParseException, InterruptedException {

        stubFor(get(urlPathEqualTo("/1111"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/xml")
                        .withBody(dialStatusCallbackOnlyInitiatedAndAnswer)));

        stubFor(post(urlPathMatching("/status.*"))
                .willReturn(aResponse()
                        .withStatus(200)));

        // Register Alice
        SipURI uri = aliceSipStack.getAddressFactory().createSipURI(null, "127.0.0.1:5080");
        assertTrue(alicePhone.register(uri, "alice", "1234", aliceContact, 3600, 3600));

        // Prepare Alice to receive call
        final SipCall aliceCall = alicePhone.createSipCall();
        aliceCall.listenForIncomingCall();

        // Create outgoing call with first phone
        final SipCall bobCall = bobPhone.createSipCall();
        bobCall.initiateOutgoingCall(bobContact, dialRestcomm, null, body, "application", "sdp", null, null);
        assertLastOperationSuccess(bobCall);
        assertTrue(bobCall.waitOutgoingCallResponse(5 * 1000));
        final int response = bobCall.getLastReceivedResponse().getStatusCode();
        assertTrue(response == Response.TRYING || response == Response.RINGING);

        if (response == Response.TRYING) {
            assertTrue(bobCall.waitOutgoingCallResponse(5 * 1000));
            assertEquals(Response.RINGING, bobCall.getLastReceivedResponse().getStatusCode());
        }

        assertTrue(bobCall.waitOutgoingCallResponse(5 * 1000));
        assertEquals(Response.OK, bobCall.getLastReceivedResponse().getStatusCode());
        bobCall.sendInviteOkAck();

        assertTrue(aliceCall.waitForIncomingCall(5000));
        assertTrue(aliceCall.sendIncomingCallResponse(Response.TRYING, "Alice-Trying", 3600));
        assertTrue(aliceCall.sendIncomingCallResponse(Response.RINGING, "Alice-Ringing", 3600));
        String receivedBody = new String(aliceCall.getLastReceivedRequest().getRawContent());
        assertTrue(aliceCall.sendIncomingCallResponse(Response.OK, "Alice-OK", 3600, receivedBody, "application", "sdp",
                null, null));
        assertTrue(aliceCall.waitForAck(5000));

        Thread.sleep(1000);
        int liveCalls = MonitoringServiceTool.getInstance().getLiveCalls(deploymentUrl.toString(),adminAccountSid, adminAuthToken);
        int liveIncomingCalls = MonitoringServiceTool.getInstance().getLiveIncomingCalls(deploymentUrl.toString(),adminAccountSid, adminAuthToken);
        int liveOutgoingCalls = MonitoringServiceTool.getInstance().getLiveOutgoingCalls(deploymentUrl.toString(),adminAccountSid, adminAuthToken);
        int liveCallsArraySize = MonitoringServiceTool.getInstance().getLiveCallsArraySize(deploymentUrl.toString(),adminAccountSid, adminAuthToken);
        assertTrue(liveCalls==2);
        assertTrue(liveIncomingCalls==1);
        assertTrue(liveOutgoingCalls==1);
        assertTrue(liveCallsArraySize==2);


        Thread.sleep(3000);
        aliceCall.listenForDisconnect();

        assertTrue(bobCall.disconnect());
        Thread.sleep(500);
        assertTrue(aliceCall.waitForDisconnect(5000));
        assertTrue(aliceCall.respondToDisconnect());

        Thread.sleep(10000);

        logger.info("About to check the StatusCallback Requests");
        List<LoggedRequest> requests = findAll(postRequestedFor(urlPathMatching("/status.*")));
        assertEquals(2, requests.size());
        String requestBody = requests.get(0).getBodyAsString();
        assertTrue(requestBody.contains("SequenceNumber=0"));
        assertTrue(requestBody.contains("CallStatus=initiated"));

        requestBody = requests.get(1).getBodyAsString();
        assertTrue(requestBody.contains("SequenceNumber=2"));
        assertTrue(requestBody.contains("CallStatus=answered"));

        liveCalls = MonitoringServiceTool.getInstance().getLiveCalls(deploymentUrl.toString(),adminAccountSid, adminAuthToken);
        liveIncomingCalls = MonitoringServiceTool.getInstance().getLiveIncomingCalls(deploymentUrl.toString(),adminAccountSid, adminAuthToken);
        liveOutgoingCalls = MonitoringServiceTool.getInstance().getLiveOutgoingCalls(deploymentUrl.toString(),adminAccountSid, adminAuthToken);
        liveCallsArraySize = MonitoringServiceTool.getInstance().getLiveCallsArraySize(deploymentUrl.toString(),adminAccountSid, adminAuthToken);
        assertTrue(liveCalls==0);
        assertTrue(liveIncomingCalls==0);
        assertTrue(liveOutgoingCalls==0);
        assertTrue(liveCallsArraySize==0);
    }

    private String dialStatusCallbackOnlyRingingCompleted = "<Response><Dial><Client statusCallback=\"http://127.0.0.1:8090/status\" " +
            "statusCallbackEvent=\"ringing,completed\">alice</Client></Dial></Response>";
    @Test
    public void testDialStatusCallbackOnlyRingingCompleted() throws ParseException, InterruptedException {

        stubFor(get(urlPathEqualTo("/1111"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/xml")
                        .withBody(dialStatusCallbackOnlyRingingCompleted)));

        stubFor(post(urlPathMatching("/status.*"))
                .willReturn(aResponse()
                        .withStatus(200)));

        // Register Alice
        SipURI uri = aliceSipStack.getAddressFactory().createSipURI(null, "127.0.0.1:5080");
        assertTrue(alicePhone.register(uri, "alice", "1234", aliceContact, 3600, 3600));

        // Prepare Alice to receive call
        final SipCall aliceCall = alicePhone.createSipCall();
        aliceCall.listenForIncomingCall();

        // Create outgoing call with first phone
        final SipCall bobCall = bobPhone.createSipCall();
        bobCall.initiateOutgoingCall(bobContact, dialRestcomm, null, body, "application", "sdp", null, null);
        assertLastOperationSuccess(bobCall);
        assertTrue(bobCall.waitOutgoingCallResponse(5 * 1000));
        final int response = bobCall.getLastReceivedResponse().getStatusCode();
        assertTrue(response == Response.TRYING || response == Response.RINGING);

        if (response == Response.TRYING) {
            assertTrue(bobCall.waitOutgoingCallResponse(5 * 1000));
            assertEquals(Response.RINGING, bobCall.getLastReceivedResponse().getStatusCode());
        }

        assertTrue(bobCall.waitOutgoingCallResponse(5 * 1000));
        assertEquals(Response.OK, bobCall.getLastReceivedResponse().getStatusCode());
        bobCall.sendInviteOkAck();

        assertTrue(aliceCall.waitForIncomingCall(5000));
        assertTrue(aliceCall.sendIncomingCallResponse(Response.TRYING, "Alice-Trying", 3600));
        assertTrue(aliceCall.sendIncomingCallResponse(Response.RINGING, "Alice-Ringing", 3600));
        String receivedBody = new String(aliceCall.getLastReceivedRequest().getRawContent());
        assertTrue(aliceCall.sendIncomingCallResponse(Response.OK, "Alice-OK", 3600, receivedBody, "application", "sdp",
                null, null));
        assertTrue(aliceCall.waitForAck(5000));

        Thread.sleep(1000);
        int liveCalls = MonitoringServiceTool.getInstance().getLiveCalls(deploymentUrl.toString(),adminAccountSid, adminAuthToken);
        int liveIncomingCalls = MonitoringServiceTool.getInstance().getLiveIncomingCalls(deploymentUrl.toString(),adminAccountSid, adminAuthToken);
        int liveOutgoingCalls = MonitoringServiceTool.getInstance().getLiveOutgoingCalls(deploymentUrl.toString(),adminAccountSid, adminAuthToken);
        int liveCallsArraySize = MonitoringServiceTool.getInstance().getLiveCallsArraySize(deploymentUrl.toString(),adminAccountSid, adminAuthToken);
        assertTrue(liveCalls==2);
        assertTrue(liveIncomingCalls==1);
        assertTrue(liveOutgoingCalls==1);
        assertTrue(liveCallsArraySize==2);


        Thread.sleep(3000);
        aliceCall.listenForDisconnect();

        assertTrue(bobCall.disconnect());
        Thread.sleep(500);
        assertTrue(aliceCall.waitForDisconnect(5000));
        assertTrue(aliceCall.respondToDisconnect());

        Thread.sleep(10000);

        logger.info("About to check the StatusCallback Requests");
        List<LoggedRequest> requests = findAll(postRequestedFor(urlPathMatching("/status.*")));
        assertEquals(2, requests.size());
        String requestBody = requests.get(0).getBodyAsString();
        assertTrue(requestBody.contains("SequenceNumber=1"));
        assertTrue(requestBody.contains("CallStatus=ringing"));

        requestBody = requests.get(1).getBodyAsString();
        assertTrue(requestBody.contains("SequenceNumber=3"));
        assertTrue(requestBody.contains("CallStatus=completed"));

        liveCalls = MonitoringServiceTool.getInstance().getLiveCalls(deploymentUrl.toString(),adminAccountSid, adminAuthToken);
        liveIncomingCalls = MonitoringServiceTool.getInstance().getLiveIncomingCalls(deploymentUrl.toString(),adminAccountSid, adminAuthToken);
        liveOutgoingCalls = MonitoringServiceTool.getInstance().getLiveOutgoingCalls(deploymentUrl.toString(),adminAccountSid, adminAuthToken);
        liveCallsArraySize = MonitoringServiceTool.getInstance().getLiveCallsArraySize(deploymentUrl.toString(),adminAccountSid, adminAuthToken);
        assertTrue(liveCalls==0);
        assertTrue(liveIncomingCalls==0);
        assertTrue(liveOutgoingCalls==0);
        assertTrue(liveCallsArraySize==0);
    }


    @Deployment(name = "DialAction", managed = true, testable = false)
    public static WebArchive createWebArchiveNoGw() {
        logger.info("Packaging Test App");
        WebArchive archive = ShrinkWrap.create(WebArchive.class, "restcomm.war");
        final WebArchive restcommArchive = ShrinkWrapMaven.resolver()
                .resolve("org.restcomm:restcomm-connect.application:war:" + version).withoutTransitivity()
                .asSingle(WebArchive.class);
        archive = archive.merge(restcommArchive);
        archive.delete("/WEB-INF/sip.xml");
        archive.delete("/WEB-INF/conf/restcomm.xml");
        archive.delete("/WEB-INF/data/hsql/restcomm.script");
        archive.delete("/WEB-INF/classes/application.conf");
        archive.addAsWebInfResource("sip.xml");
        archive.addAsWebInfResource("restcomm.xml", "conf/restcomm.xml");
        archive.addAsWebInfResource("restcomm.script_dialStatusCallbackTest", "data/hsql/restcomm.script");
        archive.addAsWebInfResource("akka_application.conf", "classes/application.conf");
        logger.info("Packaged Test App");
        return archive;
    }

}
