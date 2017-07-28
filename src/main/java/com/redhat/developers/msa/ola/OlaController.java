/**
 * JBoss, Home of Professional Open Source
 * Copyright 2016, Red Hat, Inc. and/or its affiliates, and individual
 * contributors by the @authors tag. See the copyright.txt in the
 * distribution for a full listing of individual contributors.
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.redhat.developers.msa.ola;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;

import org.jboss.jbossts.star.util.TxStatus;
import org.jboss.jbossts.star.util.TxSupport;
import org.keycloak.KeycloakPrincipal;
import org.keycloak.adapters.RefreshableKeycloakSecurityContext;
import org.keycloak.representations.AccessToken;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import io.swagger.annotations.ApiOperation;

@RestController
@RequestMapping("/api")
public class OlaController {

    private static final String txCoordinator = "http://wildfly-rts:8080";
    // private static final String txCoordinator = "http://localhost:8180";
    private static final String txCoordinatorUrl = txCoordinator + "/rest-at-coordinator/tx/transaction-manager";

    @Autowired
    private HolaService holaService;

    @CrossOrigin
    @RequestMapping(method = RequestMethod.GET, value = "/ola", produces = "text/plain")
    @ApiOperation("Returns the greeting in Portuguese")
    public String ola() {
        String hostname = System.getenv().getOrDefault("HOSTNAME", "Unknown");
        return String.format("Olá de %s", hostname);
    }

    @CrossOrigin
    @RequestMapping(method = RequestMethod.GET, value = "/ola-chaining", produces = "application/json")
    @ApiOperation("Returns the greeting plus the next service in the chain")
    public List<String> sayHelloChaining(HttpServletRequest request) {
        /*
         * KUBERNETES_PORT_53_UDP_PROTO : udp
        KUBERNETES_PORT_53_UDP_ADDR : 172.30.0.1
        OLA_SERVICE_HOST : 172.30.239.55
        OLA_SERVICE_PORT_8080_TCP : 8080
         */

        TxSupport txSupport = new TxSupport(txCoordinatorUrl);

        txSupport.startTx();

        String participantUid = Integer.toString(new Random().nextInt(Integer.MAX_VALUE) + 1);
        String header = txSupport.makeTwoPhaseAwareParticipantLinkHeader("http://ola:8080/api", /*volatile*/ false, participantUid, null);
        // String header = txSupport.makeTwoPhaseAwareParticipantLinkHeader("http://localhost:8080/api", /*volatile*/ false, participantUid, null); //localhost testing
        System.out.println("Header :" + header);
        // String enlistmentUri = txSupport.getDurableParticipantEnlistmentURI();
        String enlistmentUri = txSupport.getTxnUri();
        System.out.println("Enlistment url: " + enlistmentUri);
        String participant = new TxSupport().enlistParticipant(enlistmentUri, header);
        System.out.println("Enlisted participant url: " + participant);

        List<String> greetings = new ArrayList<>();
        greetings.add(ola());
        greetings.addAll(holaService.hola(enlistmentUri));

        txSupport.commitTx();

        return greetings;
    }

    @CrossOrigin
    @RequestMapping(method = RequestMethod.GET, value = "/ola-secured", produces = "text/plain")
    @ApiOperation("Returns a message that is only available for authenticated users")
    public String olaSecured(KeycloakPrincipal<RefreshableKeycloakSecurityContext> principal) {
        AccessToken token = principal.getKeycloakSecurityContext().getToken();
        return "This is a Secured resource. You are logged as " + token.getName();
    }

    @CrossOrigin
    @RequestMapping(method = RequestMethod.GET, value = "/logout", produces = "text/plain")
    @ApiOperation("Logout")
    public String logout() throws ServletException {
        HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
        request.logout();
        return "Logged out";
    }

    @RequestMapping(method = RequestMethod.GET, value = "/health")
    @ApiOperation("Used to verify the health of the service")
    public String health() {
        return "I'm ok";
    }

    // -------- TXN handling
    @CrossOrigin
    @RequestMapping(method = RequestMethod.PUT, value = "{wId}/terminator")
    @ApiOperation("For terminating transaction on the Ola side")
    public ResponseEntity terminate(@PathVariable("wId") String wId, @RequestBody String body) {
        TxStatus status = TxSupport.toTxStatus(body);
        System.out.println("Service: PUT request to terminate url: wId=" + wId + ", status:=" + body + ", of status: " + status);

        if (status.isPrepare()) {
            System.out.println("Service: preparing");
        } else if (status.isCommit() || status.isCommitOnePhase()) {
            System.out.println("Service: committing");
        } else if (status.isAbort()) {
            System.out.println("Service: aborting");
        } else {
            return new ResponseEntity(HttpStatus.BAD_REQUEST);
        }

        return ResponseEntity.ok(TxSupport.toStatusContent(status.name()));
    }

    @CrossOrigin
    @RequestMapping(method = RequestMethod.HEAD, value = "{pId}/participant", produces = "text/plain")
    @ApiOperation("To get link of terminator for the particular transaction")
    public ResponseEntity getTerminator(@PathVariable("pId") String wId) {
        String serviceURL = ServletUriComponentsBuilder.fromCurrentRequestUri().build().toString();
        Pattern pattern = Pattern.compile("^(.*/api)/([^/]*)/participant.*", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(serviceURL);
        matcher.find();
        System.out.println("saying we stay at place of: " + serviceURL);
        String linkHeader = new TxSupport().makeTwoPhaseAwareParticipantLinkHeader(matcher.group(1), false, matcher.group(2), null);
        System.out.println("Asked to get participant terminator info - returning: " + linkHeader);

        return ResponseEntity.ok().header("Link", linkHeader).build();
    }
}
