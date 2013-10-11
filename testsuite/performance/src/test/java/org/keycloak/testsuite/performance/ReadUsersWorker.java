package org.keycloak.testsuite.performance;

import java.util.concurrent.atomic.AtomicInteger;

import org.apache.jmeter.samplers.SampleResult;
import org.apache.jorphan.logging.LoggingManager;
import org.apache.log.Logger;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.SocialLinkModel;
import org.keycloak.models.UserModel;

/**
 * @author <a href="mailto:mposolda@redhat.com">Marek Posolda</a>
 */
public class ReadUsersWorker implements Worker {

    private static final Logger log = LoggingManager.getLoggerForClass();

    private static final int NUMBER_OF_ITERATIONS_IN_EACH_REPORT = 5000;

    // Total number of iterations read during whole test
    private static AtomicInteger totalIterationCounter = new AtomicInteger();

    // Reading users will always start from 1. Each worker thread needs to read users to single realm, which is dedicated just for this worker
    private int userCounterInRealm = 0;

    private int realmsOffset;
    private int readUsersPerIteration;
    private int countOfUsersPerRealm;
    private boolean readRoles;
    private boolean readScopes;
    private boolean readPassword;
    private boolean readSocialLinks;
    private boolean searchBySocialLinks;

    private String realmId;
    private int iterationNumber;

    @Override
    public void setup(int workerId, KeycloakSession identitySession) {
        realmsOffset = PerfTestUtils.readSystemProperty("keycloak.perf.readUsers.realms.offset", Integer.class);
        readUsersPerIteration = PerfTestUtils.readSystemProperty("keycloak.perf.readUsers.readUsersPerIteration", Integer.class);
        countOfUsersPerRealm = PerfTestUtils.readSystemProperty("keycloak.perf.readUsers.countOfUsersPerRealm", Integer.class);
        readRoles = PerfTestUtils.readSystemProperty("keycloak.perf.readUsers.readRoles", Boolean.class);
        readScopes = PerfTestUtils.readSystemProperty("keycloak.perf.readUsers.readScopes", Boolean.class);
        readPassword = PerfTestUtils.readSystemProperty("keycloak.perf.readUsers.readPassword", Boolean.class);
        readSocialLinks = PerfTestUtils.readSystemProperty("keycloak.perf.readUsers.readSocialLinks", Boolean.class);
        searchBySocialLinks = PerfTestUtils.readSystemProperty("keycloak.perf.readUsers.searchBySocialLinks", Boolean.class);

        int realmNumber = realmsOffset + workerId;
        realmId = PerfTestUtils.getRealmName(realmNumber);

        StringBuilder logBuilder = new StringBuilder("Read setup: ")
                .append("realmsOffset=" + realmsOffset)
                .append(", readUsersPerIteration=" + readUsersPerIteration)
                .append(", countOfUsersPerRealm=" + countOfUsersPerRealm)
                .append(", readRoles=" + readRoles)
                .append(", readScopes=" + readScopes)
                .append(", readPassword=" + readPassword)
                .append(", readSocialLinks=" + readSocialLinks)
                .append(", searchBySocialLinks=" + searchBySocialLinks)
                .append(", realmId=" + realmId);
        log.info(logBuilder.toString());
    }

    @Override
    public void run(SampleResult result, KeycloakSession identitySession) {
        // We need to obtain realm first
        RealmModel realm = identitySession.getRealm(realmId);
        if (realm == null) {
            throw new IllegalStateException("Realm '" + realmId + "' not found");
        }

        int totalIterationNumber = totalIterationCounter.incrementAndGet();
        String lastUsername = null;

        for (int i=0 ; i<readUsersPerIteration ; i++) {
            ++userCounterInRealm;

            // Start reading users from 1
            if (userCounterInRealm > countOfUsersPerRealm) {
                userCounterInRealm = 1;
            }

            String username = PerfTestUtils.getUsername(userCounterInRealm);
            lastUsername = username;

            UserModel user = realm.getUser(username);

            // Read roles of user in realm
            if (readRoles) {
                realm.getRoleMappings(user);
            }

            // Read scopes of user in realm
            if (readScopes) {
                realm.getScopeMappings(user);
            }

            // Validate password (shoould be same as username)
            if (readPassword) {
                realm.validatePassword(user, username);
            }

            // Read socialLinks of user
            if (readSocialLinks) {
                realm.getSocialLinks(user);
            }

            // Try to search by social links
            if (searchBySocialLinks) {
                SocialLinkModel socialLink = new SocialLinkModel("facebook", username);
                realm.getUserBySocialLink(socialLink);
            }
        }

        log.info("Finished iteration " + ++iterationNumber + " in ReadUsers test for " + realmId + " worker. Last read user " + lastUsername  + " in realm: " + realmId);

        int labelC = ((totalIterationNumber - 1) / NUMBER_OF_ITERATIONS_IN_EACH_REPORT) * NUMBER_OF_ITERATIONS_IN_EACH_REPORT;
        result.setSampleLabel("ReadUsers " + (labelC + 1) + "-" + (labelC + NUMBER_OF_ITERATIONS_IN_EACH_REPORT));
    }

    @Override
    public void tearDown() {
    }
}