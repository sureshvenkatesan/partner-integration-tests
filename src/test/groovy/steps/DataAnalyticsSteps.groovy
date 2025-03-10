package steps

import io.restassured.path.json.JsonPath
import io.restassured.response.Response
import org.awaitility.Awaitility
import org.testng.Assert
import org.testng.annotations.DataProvider
import org.yaml.snakeyaml.Yaml
import tests.TestSetup
import utils.Utils

import java.util.concurrent.TimeUnit

class DataAnalyticsSteps extends TestSetup{

    def repoSteps = new RepositorySteps()
    def securitySteps = new SecuritytSteps()
    def xraySteps = new XraySteps()
    def artifact = new File("./src/test/resources/repositories/artifact.zip")
    def repoListHA = new File("./src/test/resources/repositories/CreateDefault.yaml")
    def xrayURL = "${artifactoryBaseURL}/xray/api"
    def artifactoryURL = "${artifactoryBaseURL}/artifactory"

    def getRepos = (repoSteps.&getRepos).curry(artifactoryURL, username, password)
    def createUser = (securitySteps.&createUser).curry(artifactoryURL, username, password)
    def createPolicy = (xraySteps.&createPolicy).rcurry(username, password, xrayURL)

    // Generate HTTP responses to test Log Analytics

    def login(usernameRt, passwordRt, url, count, calls){
        while (count <= calls) {
            Response response = securitySteps.login(url, usernameRt+count, passwordRt)
            response.then().log().status()
            count++
        }
    }

    def http200(count, calls){
        while (count <= calls) {
            Response http200 = getRepos()
            http200.then().statusCode(200)
            count++
        }

    }
    def http201(count, calls){
        while (count <= calls) {
            def usernameRt = "user${count}"
            def emailRt = "email+${count}@server.com"
            def passwordRt = "Password1"
            Response http201 = createUser(usernameRt, emailRt, passwordRt)
            http201.then().log().ifValidationFails().statusCode(201)
            count++
        }
    }

    def http204(count, calls){
        while (count <= calls) {
            def path = "generic-dev-local/test-directory/artifact.zip"
            def repoName = "generic-dev-local"
            def directoryName = "test-directory"
            def filename = "artifact.zip"
            def sha256 = Utils.generateSHA256(artifact)
            def sha1 = Utils.generateSHA1(artifact)
            def md5 = Utils.generateMD5(artifact)
            def body = repoListHA
            repoSteps.createRepositories(artifactoryURL, body, username, password)
            repoSteps.deployArtifact(artifactoryURL, username, password, repoName, directoryName, artifact, filename, sha256, sha1, md5)
            Response http204 = repoSteps.deleteItem(artifactoryURL, username, password, path)
            http204.then().log().ifValidationFails().statusCode(204)
            count++
        }
    }

    def http401(count, calls){
        def repoName = "generic-dev-local"
        (count..calls) {
            repoSteps.deleteRepository(artifactoryURL, repoName, "user1", "Password1").then().log().ifValidationFails().statusCode(403)
        }
    }



    def http403(count, calls){
        while (count <= calls) {
            def repoName = "generic-dev-local"
            Response http403 = repoSteps.deleteRepository(artifactoryURL, repoName, "user1", "Password1")
            http403.then().log().ifValidationFails().statusCode(403)
            count++
        }
    }


    def createUsers401(count, calls){
        def usernameRt = "dummyuser"
        def emailRt = "email@example.com"
        def passwordRt = "Password1"
        def password = "Fakepassword1"
        while (count <= calls) {
            def username = "fakeuser-${count}"
            // In case we are rejected (too many incorrect credential requests), wait and try again.
            Awaitility.await().atMost(65, TimeUnit.SECONDS).until(() ->
                    securitySteps.createUser(artifactoryURL, username, password, usernameRt, emailRt, passwordRt).then().
                            extract().statusCode() == 401)
            count++
        }
    }

    def http404(count, calls){
        while (count <= calls) {
            def path = "generic-dev-local/test-directory/non-existing-artifact.zip"
            Response http404 = repoSteps.deleteItem(artifactoryURL, username, password, path)
            http404.then().log().ifValidationFails().statusCode(404)
            count++
        }
    }

    def http500(count, calls){
        while (count <= calls) {
            Response http500 = securitySteps.generateError500(artifactoryURL, username, password)
            http500.then().log().ifValidationFails().statusCode(500)
            count++
        }
    }

    def downloadArtifact(count, calls){
        def repoName = "generic-dev-local"
        def directoryName = "test-directory"
        def filename = "1_artifact.zip"
        while (count <= calls) {
            Response download = repoSteps.downloadArtifact(artifactoryURL, username, password, repoName, directoryName, filename)
            download.then().log().ifValidationFails().statusCode(200)
            count++
        }
    }

    def uploadIntoRepo(count, calls){
        def body = repoListHA
        def configFile = new File("./src/test/resources/testenv.yaml")
        Yaml yaml = new Yaml()
        def config = yaml.load(configFile.text)
        def username = config.artifactory.rt_username
        def password = config.artifactory.rt_password
        Response create = repoSteps.createRepositories(artifactoryURL, body, username, password)
        create.then().statusCode(200)
        def repoName = "generic-dev-local"
        def directoryName = "test-directory"
        def sha256 = Utils.generateSHA256(artifact)
        def sha1 = Utils.generateSHA1(artifact)
        def md5 = Utils.generateMD5(artifact)

        for (int i = 1; i <= calls; i++) {
            def filename = "artifact.zip"
            filename = "${i}_${filename}"
            Response deploy = repoSteps.deployArtifact(artifactoryURL, username, password, repoName, directoryName, artifact, filename, sha256, sha1, md5)
            deploy.then().log().ifValidationFails().statusCode(201)
        }
        long fileSizeInBytes = artifact.length()
        return fileSizeInBytes
    }

    def xray200(count, calls){
        while (count <= calls) {
            Response policies = xraySteps.getPolicies(username, password, xrayURL)
            policies.then().statusCode(200)

            count++
        }
    }

    def xray201(count, calls){
        Random random = new Random()
        while (count <= calls) {
            def policyName = "new-policy-(${random.nextInt(10000000)})"
            Response policy = createPolicy(policyName)
            policy.then().statusCode(201)
            count++
        }
    }

    def xray409(count, calls){
        while (count <= calls) {
            def policyName = "new-policy"
            createPolicy(policyName)
            count++
        }
    }

    def xray500(count, calls){
        while (count <= calls) {
            def policyName = "non-existing-policy"
            Response policy = xraySteps.getPolicy(policyName, username, password, xrayURL)
            policy.then().statusCode(500)
            count++
        }
    }


    def createUsers(usernameRt, emailRt, passwordRt){
        Response response = createUser(usernameRt, emailRt, passwordRt)
        response.then().log().ifValidationFails().statusCode(201)
    }

    def createRepos(){
        def body = repoListHA
        Response create = repoSteps.createRepositories(artifactoryURL, body, username, password)
        create.then().statusCode(200)
    }

    def getRepos(username, password){
        Response response = repoSteps.getReposWithUser(artifactoryURL, username, password)
        response.then().statusCode(200)
    }

    def deployArtifactAs(usernameRt, passwordRt, expectedResponseCode){
            def repoName = "generic-dev-local"
            def directoryName = "test-directory"
            def filename = "artifact-test.zip"
            def sha256 = Utils.generateSHA256(artifact)
            def sha1 = Utils.generateSHA1(artifact)
            def md5 = Utils.generateMD5(artifact)
            def body = repoListHA
            repoSteps.createRepositories(artifactoryURL, body, username, password)
            Response response = repoSteps.deployArtifactAs(artifactoryURL, usernameRt, passwordRt, repoName, directoryName, artifact, filename, sha256, sha1, md5)
            response.then().log().status()
            //response.then().log().ifValidationFails().statusCode(expectedResponseCode)
    }

    def addPermissions(usernameRt){
        def permissionName = "testPermission"
        def repository = "generic-dev-local"
        def user1 = usernameRt
        def action1 = "write"
        def action2 = "read"
        def action3 = "manage"
        Response response = securitySteps.createSinglePermission(artifactoryURL, username, password, permissionName, repository, user1,
                action1, action2, action3)
        response.then().log().ifValidationFails().statusCode(200)
    }

    def getDatadogFloatList(response){
        JsonPath jsonPathEvaluator = response.jsonPath()
        int seriesSize = response.then().extract().body().path("series.size()")
        int size = response.then().extract().body().path("series[${seriesSize-1}].pointlist.size()")
        def counter = 0
        List<Float> numbers = []
        while(counter < size){
            try {
                for(i in size){
                    float number = (jsonPathEvaluator.getString("series[${seriesSize - 1}].pointlist[${counter}][1]") as Float)
                    numbers.add(number)
                }
                counter++
            } catch (NullPointerException e){
                Assert.fail("The list of errors is empty!" + e)
            }
        }
        return numbers
    }

    def getDatadogStringList(response){
        int seriesSize = response.then().extract().body().path("series.size()")
        int size = response.then().extract().body().path("series[${seriesSize-1}].pointlist.size()")
        def counter = 0
        def numbers = []
        while(counter < size){
            for(i in size){
                String number = (response.then().extract().body().path("series[${seriesSize - 1}].pointlist[${counter}][1]"))
                if (number != null) {
                    numbers.add(number)
                }
            }
            counter++
        }
        return numbers
    }


    @DataProvider(name="users")
    public Object[][] users() {
        return new Object[][]{
                ["testuser0", "email0@jfrog.com", "Password1", "incorrectPassword"],
                ["testuser1", "email1@jfrog.com", "Password1", "incorrectPassword"],
                ["testuser2", "email2@jfrog.com", "Password1", "incorrectPassword"],
                ["testuser3", "email3@jfrog.com", "Password1", "incorrectPassword"],
                ["testuser4", "email4@jfrog.com", "Password1", "incorrectPassword"]

        }
    }


}
