package demo.webauthn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.yubico.u2f.attestation.MetadataService;
import com.yubico.u2f.crypto.BouncyCastleCrypto;
import com.yubico.u2f.crypto.ChallengeGenerator;
import com.yubico.u2f.crypto.RandomChallengeGenerator;
import com.yubico.u2f.data.messages.key.util.U2fB64Encoding;
import com.yubico.webauthn.RegistrationResult;
import com.yubico.webauthn.RelyingParty;
import com.yubico.webauthn.data.PublicKey$;
import com.yubico.webauthn.data.PublicKeyCredentialParameters;
import com.yubico.webauthn.data.RelyingPartyIdentity;
import com.yubico.webauthn.data.UserIdentity;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.time.Clock;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import lombok.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.util.Either;
import scala.util.Left;
import scala.util.Right;
import scala.util.Try;

public class WebAuthnServer {
    private static final Logger logger = LoggerFactory.getLogger(WebAuthnServer.class);

    private static final String DEFAULT_ORIGIN = "https://localhost:8443";
    private static final RelyingPartyIdentity DEFAULT_RP_ID
        = new RelyingPartyIdentity("Yubico WebAuthn demo", "localhost", Optional.empty());

    private final Cache<String, AssertionRequest> assertRequestStorage = newCache();
    private final Cache<String, RegistrationRequest> registerRequestStorage = newCache();
    private final InMemoryRegistrationStorage userStorage = new InMemoryRegistrationStorage();
    private final Cache<AssertionRequest, AuthenticatedAction> authenticatedActions = newCache();

    private final ChallengeGenerator challengeGenerator = new RandomChallengeGenerator();

    private final MetadataService metadataService = new MetadataService();

    private final Clock clock = Clock.systemDefaultZone();
    private final ObjectMapper jsonMapper = new ScalaJackson().get();


    private final RelyingParty rp = new RelyingParty(
        getRpIdFromEnv(),
        challengeGenerator,
        Collections.singletonList(new PublicKeyCredentialParameters(-7L, PublicKey$.MODULE$)),
        getOriginsFromEnv(),
        Optional.empty(),
        new BouncyCastleCrypto(),
        true,
        true,
        userStorage,
        Optional.of(metadataService),
        true,
        false
    );

    public WebAuthnServer() throws MalformedURLException {
    }

    private static <K, V> Cache<K, V> newCache() {
        return CacheBuilder.newBuilder()
            .maximumSize(100)
            .expireAfterAccess(1, TimeUnit.HOURS)
            .build();
    }

    private static List<String> getOriginsFromEnv() {
        final String origins = System.getenv("YUBICO_WEBAUTHN_ALLOWED_ORIGINS");

        logger.debug("YUBICO_WEBAUTHN_ALLOWED_ORIGINS: {}", origins);

        final List<String> result;

        if (origins == null) {
            result = Arrays.asList(DEFAULT_ORIGIN);
        } else {
            result = Arrays.asList(origins.split(","));
        }

        logger.info("Origins: {}", result);

        return result;
    }

    private static RelyingPartyIdentity getRpIdFromEnv() throws MalformedURLException {
        final String name = System.getenv("YUBICO_WEBAUTHN_RP_NAME");
        final String id = System.getenv("YUBICO_WEBAUTHN_RP_ID");
        final String icon = System.getenv("YUBICO_WEBAUTHN_RP_ICON");

        logger.debug("RP name: {}", name);
        logger.debug("RP ID: {}", id);
        logger.debug("RP icon: {}", icon);

        final RelyingPartyIdentity result;

        if (name == null || id == null) {
            logger.debug("RP name or ID not given - using default.");
            result = DEFAULT_RP_ID;
        } else {
            Optional<URL> iconUrl = Optional.empty();
            if (icon != null) {
                try {
                    iconUrl = Optional.of(new URL(icon));
                } catch (MalformedURLException e) {
                    logger.error("Invalid icon URL: {}", icon, e);
                    throw e;
                }
            }
            result = new RelyingPartyIdentity(name, id, iconUrl);
        }

        logger.info("RP identity: {}", result);

        return result;
    }

    public RegistrationRequest startRegistration(String username, String displayName, String credentialNickname) {
        logger.trace("startRegistration username: {}, credentialNickname: {}", username, credentialNickname);

        byte[] userId = challengeGenerator.generateChallenge();

        RegistrationRequest request = new RegistrationRequest(
            username,
            credentialNickname,
            U2fB64Encoding.encode(challengeGenerator.generateChallenge()),
            rp.startRegistration(
                new UserIdentity(username, displayName, userId, Optional.empty()),
                Optional.of(userStorage.getCredentialIdsForUsername(username)),
                Optional.empty()
            )
        );
        registerRequestStorage.put(request.getRequestId(), request);
        return request;
    }

    @Value
    public static class SuccessfulRegistrationResult {
        final boolean success = true;
        RegistrationRequest request;
        RegistrationResponse response;
        CredentialRegistration registration;
        boolean attestationTrusted;
    }

    public Either<List<String>, SuccessfulRegistrationResult> finishRegistration(String responseJson) {
        logger.trace("finishRegistration responseJson: {}", responseJson);
        RegistrationResponse response = null;
        try {
            response = jsonMapper.readValue(responseJson, RegistrationResponse.class);
        } catch (IOException e) {
            logger.error("JSON error in finishRegistration; responseJson: {}", responseJson, e);
            return Left.apply(Arrays.asList("Registration failed!", "Failed to decode response object.", e.getMessage()));
        }

        RegistrationRequest request = registerRequestStorage.getIfPresent(response.getRequestId());
        registerRequestStorage.invalidate(response.getRequestId());

        if (request == null) {
            logger.debug("fail finishRegistration responseJson: {}", responseJson);
            return Left.apply(Arrays.asList("Registration failed!", "No such registration in progress."));
        } else {
            Try<RegistrationResult> registrationTry = rp.finishRegistration(
                request.getMakePublicKeyCredentialOptions(),
                response.getCredential(),
                Optional.empty()
            );

            if (registrationTry.isSuccess()) {
                return Right.apply(
                    new SuccessfulRegistrationResult(
                        request,
                        response,
                        addRegistration(
                            request.getUsername(),
                            request.getCredentialNickname(),
                            request.getMakePublicKeyCredentialOptions().user().idBase64(),
                            response,
                            registrationTry.get()
                        ),
                        registrationTry.get().attestationTrusted()
                    )
                );
            } else {
                logger.debug("fail finishRegistration responseJson: {}", responseJson, registrationTry.failed().get());
                return Left.apply(Arrays.asList("Registration failed!", registrationTry.failed().get().getMessage()));
            }

        }
    }

    public AssertionRequest startAuthentication(Optional<String> username) {
        logger.trace("startAuthentication username: {}", username);
        AssertionRequest request = new AssertionRequest(
            username,
            U2fB64Encoding.encode(challengeGenerator.generateChallenge()),
            rp.startAssertion(
                username.map(userStorage::getCredentialIdsForUsername),
                Optional.empty()
            )
        );

        assertRequestStorage.put(request.getRequestId(), request);

        return request;
    }

    @Value
    public static class SuccessfulAuthenticationResult {
        final boolean success = true;
        AssertionRequest request;
        AssertionResponse response;
        Collection<CredentialRegistration> registrations;
    }

    public Either<List<String>, SuccessfulAuthenticationResult> finishAuthentication(String responseJson) {
        logger.trace("finishAuthentication responseJson: {}", responseJson);

        final AssertionResponse response;
        try {
            response = jsonMapper.readValue(responseJson, AssertionResponse.class);
        } catch (IOException e) {
            logger.debug("Failed to decode response object", e);
            return Left.apply(Arrays.asList("Assertion failed!", "Failed to decode response object.", e.getMessage()));
        }

        AssertionRequest request = assertRequestStorage.getIfPresent(response.getRequestId());
        assertRequestStorage.invalidate(response.getRequestId());

        if (request == null) {
            return Left.apply(Arrays.asList("Assertion failed!", "No such assertion in progress."));
        } else {
            Optional<Boolean> credentialIsAllowed = request.getPublicKeyCredentialRequestOptions().allowCredentials().map(allowCredentials ->
                allowCredentials.stream().anyMatch(credential ->
                    credential.idBase64().equals(response.getCredential().id())
                )
            );

            Optional<String> userHandle = Optional.ofNullable(response.getCredential().response().userHandleBase64());

            if (!request.getUsername().isPresent() && !userHandle.isPresent()) {
                return Left.apply(Arrays.asList("User handle must be returned if username was not supplied in startAuthentication"));
            } else {
                final String username;
                if (request.getUsername().isPresent()) {
                    username = request.getUsername().get();
                } else {
                    username = userStorage.getUsername(userHandle.get()).orElse(null);
                }

                if (username == null) {
                    return Left.apply(Arrays.asList("User not registered: " + request.getUsername().orElse(userHandle.get())));
                } else {
                    boolean usernameOwnsCredential = userStorage.usernameOwnsCredential(username, response.getCredential().id());

                    if (credentialIsAllowed.isPresent() && !credentialIsAllowed.get()) {
                        return Left.apply(Collections.singletonList(String.format(
                            "Credential is not allowed for this authentication: %s",
                            response.getCredential().id()
                        )));
                    } else if (!usernameOwnsCredential) {
                        return Left.apply(Collections.singletonList(String.format(
                            "User \"%s\" does not own credential: %s",
                            request.getUsername(),
                            response.getCredential().id()
                        )));
                    } else {
                        Try<Object> assertionTry = rp.finishAssertion(
                            request.getPublicKeyCredentialRequestOptions(),
                            response.getCredential(),
                            Optional.empty()
                        );

                        if (assertionTry.isSuccess()) {
                            if ((boolean) assertionTry.get()) {
                                final CredentialRegistration credentialRegistration = userStorage.getRegistrationByUsernameAndCredentialId(
                                    username,
                                    response.getCredential().id()
                                ).get();

                                final CredentialRegistration updatedCredReg = credentialRegistration.withSignatureCount(
                                    response.getCredential().response().parsedAuthenticatorData().signatureCounter()
                                );

                                try {
                                    userStorage.updateSignatureCountForUsername(
                                        username,
                                        response.getCredential().id(),
                                        response.getCredential().response().parsedAuthenticatorData().signatureCounter()
                                    );
                                } catch (Exception e) {
                                    logger.error(
                                        "Failed to update signature count for user \"{}\", credential \"{}\"",
                                        request.getUsername(),
                                        response.getCredential().id(),
                                        e
                                    );
                                }

                                return Right.apply(
                                    new SuccessfulAuthenticationResult(
                                        request,
                                        response,
                                        userStorage.getRegistrationsByUsername(username)
                                    )
                                );
                            } else {
                                return Left.apply(Arrays.asList("Assertion failed: Invalid assertion."));
                            }

                        } else {
                            logger.debug("Assertion failed", assertionTry.failed().get());
                            return Left.apply(Arrays.asList("Assertion failed!", assertionTry.failed().get().getMessage()));
                        }
                    }
                }
            }
        }
    }

    public AssertionRequest startAuthenticatedAction(Optional<String> username, AuthenticatedAction<?> action) {
        final AssertionRequest request = startAuthentication(username);
        synchronized (authenticatedActions) {
            authenticatedActions.put(request, action);
        }
        return request;
    }

    public Either<List<String>, ?> finishAuthenticatedAction(String responseJson) {
        return com.yubico.util.Either.fromScala(finishAuthentication(responseJson))
            .flatMap(result -> {
                AuthenticatedAction<?> action = authenticatedActions.getIfPresent(result.request);
                authenticatedActions.invalidate(result.request);
                if (action == null) {
                    return com.yubico.util.Either.left(Collections.singletonList(
                        "No action was associated with assertion request ID: " + result.getRequest().getRequestId()
                    ));
                } else {
                    return com.yubico.util.Either.fromScala(action.apply(result));
                }
            })
            .toScala();
    }

    public <T> Either<List<String>, AssertionRequest> deregisterCredential(String username, String credentialId, Function<CredentialRegistration, T> resultMapper) {
        logger.trace("deregisterCredential username: {}, credentialId: {}", username, credentialId);

        if (username == null || username.isEmpty()) {
            return Left.apply(Arrays.asList("Username must not be empty."));
        }

        if (credentialId == null || credentialId.isEmpty()) {
            return Left.apply(Arrays.asList("Credential ID must not be empty."));
        }

        AuthenticatedAction<T> action = (SuccessfulAuthenticationResult result) -> {
            Optional<CredentialRegistration> credReg = userStorage.getRegistrationByUsernameAndCredentialId(username, credentialId);

            if (credReg.isPresent()) {
                userStorage.removeRegistrationByUsername(username, credReg.get());
                return Right.apply(resultMapper.apply(credReg.get()));
            } else {
                return Left.apply(Arrays.asList("Credential ID not registered:" + credentialId));
            }
        };

        return Right.apply(startAuthenticatedAction(Optional.of(username), action));
    }

    private CredentialRegistration addRegistration(String username, String nickname, String userHandleBase64, RegistrationResponse response, RegistrationResult registration) {
        CredentialRegistration reg = CredentialRegistration.builder()
            .username(username)
            .credentialNickname(nickname)
            .registrationTime(clock.instant())
            .registration(registration)
            .userHandleBase64(userHandleBase64)
            .signatureCount(response.getCredential().response().attestation().authenticatorData().signatureCounter())
            .build();

        logger.debug(
            "Adding registration: username: {}, nickname: {}, registration: {}, credentialId: {}, public key cose: {}",
            username,
            nickname,
            registration,
            registration.keyId().idBase64(),
            registration.publicKeyCose()
        );
        userStorage.addRegistrationByUsername(username, reg);
        return reg;
    }
}
