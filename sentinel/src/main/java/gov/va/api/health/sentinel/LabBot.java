package gov.va.api.health.sentinel;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gov.va.api.health.sentinel.selenium.IdMeOauthRobot;
import gov.va.api.health.sentinel.selenium.IdMeOauthRobot.Configuration;
import gov.va.api.health.sentinel.selenium.IdMeOauthRobot.Configuration.Authorization;
import gov.va.api.health.sentinel.selenium.IdMeOauthRobot.Configuration.Authorization.AuthorizationBuilder;
import gov.va.api.health.sentinel.selenium.IdMeOauthRobot.Configuration.UserCredentials;
import gov.va.api.health.sentinel.selenium.IdMeOauthRobot.TokenExchange;
import io.restassured.RestAssured;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import lombok.Builder;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.BooleanUtils;

@Slf4j
@Value
public class LabBot {

  List<String> scopes;

  Config labConfig;

  List<String> userIds;

  /**
   * Builds what is required by LabBot.
   *
   * @param scopes scopes that LabBot will for token requests
   * @param userIds userIds that LabBot will get tokens and make requests against
   * @param configFile configFile that LabBot grabs its configuration properties from
   */
  @Builder
  public LabBot(List<String> scopes, List<String> userIds, String configFile) {
    this.scopes = scopes;
    this.userIds = userIds;
    labConfig = new LabBot.Config(configFile);
  }

  /** Gets all Lab users. */
  public static List<String> allUsers() {
    List<String> allUsers = new LinkedList<>();
    for (int i = 1; i <= 5; i++) {
      allUsers.add("vasdvp+IDME_" + String.format("%02d", i) + "@gmail.com");
    }
    for (int i = 101; i < 183; i++) {
      allUsers.add("va.api.user+idme." + String.format("%03d", i) + "@gmail.com");
    }
    return allUsers;
  }

  private Authorization makeAuthorization(SmartOnFhirUrls urls) {
    AuthorizationBuilder authorizationBuilder = Authorization.builder();
    authorizationBuilder
        .clientId(labConfig.clientId())
        .clientSecret(labConfig.clientSecret())
        .authorizeUrl(urls.authorize())
        .redirectUrl(labConfig.redirectUrl())
        .state(labConfig.state())
        .aud(labConfig.aud());
    for (String scope : scopes) {
      authorizationBuilder.scope(scope);
    }
    return authorizationBuilder.build();
  }

  /**
   * Creates IdMeOauthRobot with specified user credentials and urls for Lab environment using
   * Chrome Driver.
   *
   * @param userCredentials Credentials for the user to perform operations against.
   * @param baseUrl URLs to perform operations against.
   */
  public IdMeOauthRobot makeLabBot(UserCredentials userCredentials, String baseUrl) {
    SmartOnFhirUrls urls = new SmartOnFhirUrls(baseUrl);
    Authorization authorization = makeAuthorization(urls);
    Configuration configuration =
        Configuration.builder()
            .authorization(authorization)
            .tokenUrl(urls.token())
            .user(userCredentials)
            .chromeDriver(labConfig.driver())
            .headless(labConfig.headless())
            .build();
    IdMeOauthRobot bot = IdMeOauthRobot.of(configuration);
    return bot;
  }

  /**
   * Given a path send a request for each user. Replaces {icn} with patient icn to send request.
   * Useful for when you want to send a given request to a set of users.
   *
   * @param path The path to use for the requests, must contain {icn} to be replaced with the
   *     patient icn.
   */
  @SneakyThrows
  public List<LabBotUserResult> request(String path) {
    List<LabBotUserResult> tokenUserResultList = tokens();
    List<LabBotUserResult> responseUserResultList = new CopyOnWriteArrayList<>();
    ExecutorService ex = Executors.newFixedThreadPool(10);
    List<Future<?>> futures = new ArrayList<>(userIds.size());
    for (LabBotUserResult tokenUserResult : tokenUserResultList) {
      futures.add(
          ex.submit(
              () -> {
                try {
                  URL baseUrl = new URL(labConfig.baseUrl());
                  URL url =
                      new URL(
                          baseUrl.getProtocol(),
                          baseUrl.getHost(),
                          path.replace("{icn}", tokenUserResult.tokenExchange.patient()));
                  HttpURLConnection con = (HttpURLConnection) url.openConnection();
                  con.setRequestProperty(
                      "Authorization", "Bearer " + tokenUserResult.tokenExchange.accessToken());
                  con.setRequestMethod("GET");
                  log.info("Sending request to: " + url.toString());
                  BufferedReader br =
                      new BufferedReader(new InputStreamReader(con.getInputStream(), "UTF-8"));
                  responseUserResultList.add(
                      new LabBotUserResult(
                          tokenUserResult.user, tokenUserResult.tokenExchange, br.readLine()));
                  br.close();
                } catch (Exception e) {
                  log.error(e.getMessage(), e.getCause());
                }
              }));
    }
    results(ex, futures);
    return responseUserResultList;
  }

  private void results(ExecutorService ex, List<Future<?>> futures) throws InterruptedException {
    ex.shutdown();
    ex.awaitTermination(10, TimeUnit.MINUTES);
    futures.forEach(
        f -> {
          try {
            f.get();
          } catch (Exception e) {
            log.error(e.getMessage());
          }
        });
  }

  /** Returns tokens for each user. */
  @SneakyThrows
  public List<LabBotUserResult> tokens() {
    List<LabBotUserResult> labBotUserResultList = new CopyOnWriteArrayList<>();
    ExecutorService ex = Executors.newFixedThreadPool(10);
    List<Future<?>> futures = new ArrayList<>(userIds.size());
    for (String userId : userIds) {
      futures.add(
          ex.submit(
              () -> {
                UserCredentials userCredentials =
                    UserCredentials.builder().id(userId).password(labConfig.userPassword()).build();
                IdMeOauthRobot bot = makeLabBot(userCredentials, labConfig.baseUrl());
                labBotUserResultList.add(
                    LabBotUserResult.builder()
                        .user(userCredentials)
                        .tokenExchange(bot.token())
                        .build());
              }));
    }
    results(ex, futures);
    return labBotUserResultList;
  }

  private static class Config {

    private Properties properties;

    private String env;

    @SneakyThrows
    Config(String pathname) {
      File file = new File(pathname);
      int slashIndex = file.toString().lastIndexOf('\\');
      int dotIndex = file.toString().lastIndexOf('.');
      env = file.toString().substring(slashIndex + 1, dotIndex);
      if (file.exists()) {
        log.info("Loading {} properties from: {}", env, file);
        properties = new Properties(System.getProperties());
        try (FileInputStream inputStream = new FileInputStream(file)) {
          properties.load(inputStream);
        }
      } else {
        log.info("{} properties not found: {}, using System properties", env, file);
        properties = System.getProperties();
      }
    }

    String aud() {
      return valueOf(env + ".aud");
    }

    String baseUrl() {
      return valueOf(env + ".base-url");
    }

    String clientId() {
      return valueOf(env + ".client-id");
    }

    String clientSecret() {
      return valueOf(env + ".client-secret");
    }

    String driver() {
      return valueOf("webdriver.chrome.driver");
    }

    boolean headless() {
      return BooleanUtils.toBoolean(valueOf("webdriver.chrome.headless"));
    }

    String redirectUrl() {
      return valueOf(env + ".redirect-url");
    }

    String state() {
      return valueOf(env + ".state");
    }

    String userPassword() {
      return valueOf(env + ".user-password");
    }

    private String valueOf(String name) {
      String value = properties.getProperty(name, "");
      assertThat(value).withFailMessage("System property %s must be specified.", name).isNotBlank();
      return value;
    }
  }

  public static class InvalidConformanceStatement extends RuntimeException {
    InvalidConformanceStatement(String message) {
      super(message);
    }
  }

  @Builder
  @Value
  public static class LabBotUserResult {

    UserCredentials user;

    TokenExchange tokenExchange;

    String response;
  }

  @Value
  private static class SmartOnFhirUrls {

    String token;

    String authorize;

    /**
     * Create a new instance that will reach out to the given base URL to discover SMART on FHIR
     * information. This class will attempt to interact with /metadata endpoint of the base URL
     * immediately during construction.
     */
    @SneakyThrows
    private SmartOnFhirUrls(String baseUrl) {
      log.info("Discovering authorization endpoints from {}", baseUrl);
      String statement =
          RestAssured.given().relaxedHTTPSValidation().baseUri(baseUrl).get("metadata").asString();
      ObjectMapper mapper = new ObjectMapper();
      JsonNode node = mapper.readTree(statement);
      JsonNode oauthExtensionNode = findOauthExtensionNode(node);
      token = findTokenUri(oauthExtensionNode);
      authorize = findAuthorizeUri(oauthExtensionNode);
    }

    private static void checkConformanceStatement(boolean ok, String message) {
      if (!ok) {
        throw new InvalidConformanceStatement(message);
      }
    }

    private static <T> Optional<T> checkConformanceStatement(
        Optional<T> notNullObject, String message) {
      checkConformanceStatement(notNullObject != null, message);
      checkConformanceStatement(notNullObject.isPresent(), message);
      return notNullObject;
    }

    private static <T> T checkConformanceStatement(T notNullObject, String message) {
      checkConformanceStatement(notNullObject != null, message);
      return notNullObject;
    }

    private static String findAuthorizeUri(JsonNode jsonNode) {
      Optional<JsonNode> urlAuthorizeNode =
          checkConformanceStatement(
              getParentWith(jsonNode, "url", "authorize"),
              "Unable to find JSON node with 'url:authorize' key value pair");
      JsonNode authorizeNode =
          checkConformanceStatement(
              urlAuthorizeNode.get().path("valueUri"),
              "Unable to find JSON node with 'valueUri' path for authorize");
      return authorizeNode.textValue();
    }

    private static JsonNode findOauthExtensionNode(@NonNull JsonNode jsonNode) {
      JsonNode restNode =
          checkConformanceStatement(
              jsonNode.path("rest"), "Unable to find JSON node with 'rest' path");
      Optional<JsonNode> modeServerNode =
          checkConformanceStatement(
              getParentWith(restNode, "mode", "server"),
              "Unable to find child JSON node with 'mode:server' key:value pair");
      JsonNode securityNode =
          checkConformanceStatement(
              modeServerNode.get().path("security"),
              "Unable to find JSON node with 'security' path");
      JsonNode extensionNode =
          checkConformanceStatement(
              securityNode.path("extension"),
              "Unable to find JSON node with 'extension' path for securityNode");
      Optional<JsonNode> oauthUriNode =
          checkConformanceStatement(
              getParentWith(
                  extensionNode,
                  "url",
                  "http://fhir-registry.smarthealthit.org/StructureDefinition/oauth-uris"),
              "Unable to find JSON node with 'url:http://fhir-registry.smarthealthit.org/StructureDefinition/oauth-uris' key value pair");
      return checkConformanceStatement(
          oauthUriNode.get().path("extension"),
          "Unable to find JSON node with 'extension' path for oauthUriNode");
    }

    private static String findTokenUri(JsonNode oauthUriNode) {
      Optional<JsonNode> urlTokenNode =
          checkConformanceStatement(
              getParentWith(oauthUriNode, "url", "token"),
              "Unable to find JSON node with 'url:token' key value pair");
      JsonNode tokenNode =
          checkConformanceStatement(
              urlTokenNode.get().path("valueUri"),
              "Unable to find JSON node with 'valueUri' path for token");
      return tokenNode.textValue();
    }

    /** Get json parent node with given key value pair. * */
    private static Optional<JsonNode> getParentWith(
        JsonNode node, @NonNull String key, @NonNull String value) {
      for (JsonNode checkNode : node) {
        if ((value).equals(checkNode.path(key).textValue())) {
          return Optional.of(checkNode);
        }
      }
      return Optional.empty();
    }
  }
}