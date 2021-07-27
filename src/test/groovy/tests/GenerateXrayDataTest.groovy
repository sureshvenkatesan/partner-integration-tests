package tests


import io.restassured.response.Response
import org.awaitility.Awaitility
import org.hamcrest.Matchers
import org.testng.Assert
import org.testng.Reporter
import org.testng.annotations.AfterSuite
import org.testng.annotations.BeforeSuite
import org.testng.annotations.Test
import steps.RepositorySteps
import steps.XraySteps
import utils.Utils

import java.util.concurrent.TimeUnit

import static org.hamcrest.Matchers.containsString
import static org.hamcrest.Matchers.equalTo

class GenerateXrayDataTest extends XraySteps{
    def xraySteps = new XraySteps()
    def repositorySteps = new RepositorySteps()
    def xrayBaseUrl
    def randomIndex
    def securityPolicyName
    def licensePolicyName
    def watchName
    def UILoginHeaders
    def repoListHA = new File("./src/test/resources/repositories/CreateDefault.yaml")
    def artifactoryURL = "${artifactoryBaseURL}/artifactory"
    def artifactCount = 10
    def artifactsPath = "./src/test/resources/repositories/"
    def artifactFormat = {int i -> "artifact_${i}.zip"}


    @BeforeSuite(groups = ["xray_generate_data"])
    def setUp() {
        xrayBaseUrl = "${artifactoryBaseURL}/xray/api"
        Random random = new Random()
        randomIndex = random.nextInt(10000000)
        securityPolicyName = "security_policy_${randomIndex}"
        licensePolicyName = "license_policy_${randomIndex}"
        watchName = "all-repositories_${randomIndex}"
        UILoginHeaders = getUILoginHeaders("${artifactoryBaseURL}", username, password)

        // Create zip file
        for (def i in 0..(artifactCount-1)) {
            Utils.createArtifact(artifactFormat(i))
        }
    }

    @AfterSuite(groups = ["xray_generate_data"])
    def cleanUp() {
        for (def i in 0..(artifactCount-1)) {
            def artifact = new File("${artifactsPath}${artifactFormat(i)}")
            try {
                if(artifact.delete()) {
                    println("Deleted local ${artifactFormat(i)}")
                } else {
                    println("Could not delete ${artifactFormat(i)}.")
                }
            } catch (SecurityException ignored) {
                println("Could not delete ${artifactFormat(i)}.")
            }
        }
    }

    // Push several docker images/artifacts? What if there is no SSL, then no docker
    // Generate issue types for these images: License, Security
    // Assign custom issues to these artifacts

    @Test(priority=1, groups=["xray_generate_data"], testName = "Create a list of repositories for HA, specified in YAML file")
    void createDefaultHAReposTest(){
        def body
        def expectedMessage
        body = repoListHA
        expectedMessage = "383 changes to config merged successfully"
        Response response = repositorySteps.createRepositories(artifactoryURL, body, username, password)
        response.then().assertThat().log().ifValidationFails().statusCode(200)
                .body(Matchers.hasToString(expectedMessage))
                .log().body()

        Reporter.log("- Create repositories for HA distribution. Successfully created", true)
    }

    @Test(priority=2, groups=["xray_generate_data"], testName = "Create security policy and watch. Assign policy to watch")
    void createSecurityPolicyTest(){
        Response createSecurityPolicy = xraySteps.createPolicy(securityPolicyName, username, password, xrayBaseUrl)
        createSecurityPolicy.then().statusCode(201)
        Response getPolicy = xraySteps.getPolicy(securityPolicyName, username, password, xrayBaseUrl)
        getPolicy.then().statusCode(200)
        def policyNameVerification = getPolicy.then().extract().path("name")
        Assert.assertTrue(securityPolicyName == policyNameVerification)
        xraySteps.createWatch(watchName + "_security", securityPolicyName, "security", username, password, xrayBaseUrl)

        Reporter.log("- Create policies and assign them to watches.", true)
    }

    @Test(priority=2, groups=["xray_generate_data"], dataProvider = "multipleLicenseIssueEvents", testName = "Create license policy and watch. Assign policy to watch")
    void createLicensePolicyTest(license, _, __){
        Response createLicensePolicy = xraySteps.createLicensePolicy("${ licensePolicyName }_${license}", username, password, xrayBaseUrl, license)
        createLicensePolicy.then().statusCode(201)
        Response getLicensePolicy = xraySteps.getPolicy("${ licensePolicyName }_${license}", username, password, xrayBaseUrl)
        getLicensePolicy.then().statusCode(200)
        def licensePolicyNameVerification = getLicensePolicy.then().extract().path("name")
        Assert.assertTrue(licensePolicyName+"_${license}" == licensePolicyNameVerification)

        xraySteps.createWatch("${ watchName }_license_${license}", "${licensePolicyName}_${license}", "license", username, password, xrayBaseUrl)
    }


    @Test(priority=3, groups=["xray_generate_data"], dataProvider = "artifacts", testName = "Deploy files to generic repo")
    void deployArtifactToGenericTest(artifactName){
        def repoName = "generic-dev-local"
        def directoryName = "test-directory"
        def filename = artifactName
        def artifact = new File("${artifactsPath}${filename}")
        def sha256 = Utils.generateSHA256(artifact)
        def sha1 = Utils.generateSHA1(artifact)
        def md5 = Utils.generateMD5(artifact)
        Response response = repositorySteps.deployArtifact(artifactoryURL, username, password, repoName,
                directoryName, artifact, filename, sha256, sha1, md5)
        response.then().assertThat().log().ifValidationFails().statusCode(201)
                .body("repo", equalTo(repoName))
                .body("path", equalTo("/" + directoryName + "/" + filename))
                .body("downloadUri", containsString("/artifactory/" + repoName + "/" +
                        directoryName + "/" + filename))
                .body("checksums.sha1", equalTo(sha1))
                .body("checksums.md5", equalTo(md5))
                .body("checksums.sha256", equalTo(sha256))
                .body("originalChecksums.sha1", equalTo(sha1))
                .body("originalChecksums.md5", equalTo(md5))
                .body("originalChecksums.sha256", equalTo(sha256))

        Reporter.log("- Deploy artifact. Artifact successfully deployed", true)
    }


    @Test(priority = 4, groups = ["xray_generate_data"], dataProvider = "multipleIssueEvents", testName = "Create Issue Events")
    void createSecurityIssueEventsTest(issueID, cve, summary, description, issueType, severity) {
        for (i in 0..(artifactCount - 1)) {
            def artifactName = artifactFormat(i)
            def artifact = new File("${artifactsPath}${artifactName}")
            def sha256 = Utils.generateSHA256(artifact)
            Response create = xraySteps.createSecurityIssueEvents(issueID + artifactName + randomIndex, cve, summary,
                    description, issueType, severity, sha256, artifactName, username, password, xrayBaseUrl)
            create.then().log().ifValidationFails().statusCode(201)

            Response get = xraySteps.getIssueEvent(issueID + artifactName + randomIndex, username, password, xrayBaseUrl)
            get.then().statusCode(200).log().ifValidationFails()
            def issueIDverification = get.then().extract().path("id")
            def cveVerification = get.then().extract().path("source_id")
            def summaryVerification = get.then().extract().path("summary")
            def descriptionVerification = get.then().extract().path("description")
            Assert.assertTrue(issueID + artifactName + randomIndex == issueIDverification)
            Assert.assertTrue(cve == cveVerification)
            Assert.assertTrue(summary == summaryVerification)
            Assert.assertTrue(description == descriptionVerification)
            Reporter.log("- Create issue event for ${artifactName}. Issue event with ID ${issueID + randomIndex} created and verified successfully", true)
        }
    }


    @Test(priority = 5, groups = ["xray_generate_data"], dataProvider = "multipleLicenseIssueEvents")
    void createLicenseEventsTest(license_name, license_full_name, license_references) {
        for (i in 0..(artifactCount - 1)) {
            def artifactName = artifactFormat(i)
            def artifact = new File("${artifactsPath}${artifactName}")
            def sha256 = Utils.generateSHA256(artifact)
            sleep(1000)  // UI requests are finicky. Let server settle.
            int tries = 0
            Awaitility.await().atMost(120, TimeUnit.SECONDS).with()
                    .pollDelay(1, TimeUnit.SECONDS).and().pollInterval(500, TimeUnit.MILLISECONDS).until { ->
                println "$artifactName try: ${tries++}"
                def success = assignLicenseToArtifact(UILoginHeaders, artifactoryBaseURL, artifactName, sha256,
                        license_name, license_full_name, license_references)
                .then().extract().statusCode() == 200
                if(!success) {
                    // reauthenticate
                    UILoginHeaders = getUILoginHeaders("${artifactoryBaseURL}", username, password)
                }
                return success
            }

            Reporter.log("- Assigned ${artifactName} ${license_name}", true)
        }
    }


    @Test(priority=6, groups=["xray_generate_data"], dataProvider="artifacts", testName = "Download artifacts with vulnerabilities")
    void downloadArtifactsTest(artifactName){
        def repoName = "generic-dev-local"
        def directoryName = "test-directory"
        Response response = repositorySteps.downloadArtifact(artifactoryURL, username, password, repoName,
                directoryName, artifactName)
        response.then().log().ifValidationFails().statusCode(200)
        Reporter.log("- Downloaded ${artifactName} with vulnerabilities", true)
    }


    @Test(priority=7, groups=["xray_generate_data"], testName = "Get artifact summary")
    void artifactSummaryTest(){
        def artifactPath = "default/docker-local/nginx/1.0.0/"
        Response post = artifactSummary(username, password, artifactPath, xrayBaseUrl)
        post.then().statusCode(200).log().body()
                .body("artifacts[0].general.path", equalTo(artifactPath))

        Reporter.log("- Get artifact summary. Artifact summary has been returned successfully", true)
    }


    @Test(priority=8, groups=["xray_generate_data"], testName = "Get violations")
    void getViolationsTest(){
        Response post = xrayGetViolations("license",
                username, password, xrayBaseUrl)
        post.then().log().body()

        Reporter.log("- Get violations", true)
    }


}
