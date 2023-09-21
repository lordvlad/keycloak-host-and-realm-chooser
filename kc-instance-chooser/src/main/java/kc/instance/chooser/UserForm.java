package kc.instance.chooser;

import static org.keycloak.services.validation.Validation.FIELD_USERNAME;

import java.net.URI;

import org.jboss.resteasy.specimpl.MultivaluedMapImpl;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.authenticators.browser.AbstractUsernameFormAuthenticator;
import org.keycloak.authentication.authenticators.browser.UsernamePasswordForm;
import org.keycloak.events.Details;
import org.keycloak.events.Errors;
import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.forms.login.freemarker.LoginFormsUtil;
import org.keycloak.models.ModelDuplicateException;
import org.keycloak.models.UserModel;
import org.keycloak.models.utils.FormMessage;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.protocol.oidc.OIDCLoginProtocol;
import org.keycloak.services.ServicesLogger;
import org.keycloak.services.managers.AuthenticationManager;
import org.keycloak.services.messages.Messages;

import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;

public final class UserForm extends UsernamePasswordForm {
    private HostResolver resolver;

    public UserForm(HostResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        if (context.getUser() != null) {
            // We can skip the form when user is re-authenticating. Unless current user has
            // some IDP set, so he can re-authenticate with that IDP
            var identityProviders = LoginFormsUtil
                    .filterIdentityProviders(
                            context.getRealm().getIdentityProvidersStream(),
                            context.getSession(),
                            context);
            if (identityProviders.isEmpty()) {
                context.success();
                return;
            }
        }
        authenticate2(context);
    }

    private void authenticate2(AuthenticationFlowContext context) {
        var formData = new MultivaluedMapImpl<String, String>();
        var loginHint = context.getAuthenticationSession().getClientNote(OIDCLoginProtocol.LOGIN_HINT_PARAM);

        var rememberMeUsername = AuthenticationManager.getRememberMeUsername(context.getRealm(),
                context.getHttpRequest().getHttpHeaders());

        if (context.getUser() != null) {
            var form = context.form();
            form.setAttribute(LoginFormsProvider.USERNAME_HIDDEN, true);
            form.setAttribute(LoginFormsProvider.REGISTRATION_DISABLED, true);
            context.getAuthenticationSession().setAuthNote(USER_SET_BEFORE_USERNAME_PASSWORD_AUTH, "true");

        } else {
            context.getAuthenticationSession().removeAuthNote(USER_SET_BEFORE_USERNAME_PASSWORD_AUTH);

            var nonce = context.getUriInfo().getQueryParameters().getFirst("nonce");
            var state = context.getUriInfo().getQueryParameters().getFirst("state");
            var response_mode = context.getUriInfo().getQueryParameters().getFirst("response_mode");
            var response_type = context.getUriInfo().getQueryParameters().getFirst("response_type");
            var scope = context.getUriInfo().getQueryParameters().getFirst("scope");
            var code_challenge = context.getUriInfo().getQueryParameters().getFirst("code_challenge");
            var code_challenge_method = context.getUriInfo().getQueryParameters().getFirst("code_challenge_method");

            context.getAuthenticationSession().setAuthNote("nonce", nonce);
            context.getAuthenticationSession().setAuthNote("state", state);
            context.getAuthenticationSession().setAuthNote("response_mode", response_mode);
            context.getAuthenticationSession().setAuthNote("response_type", response_type);
            context.getAuthenticationSession().setAuthNote("scope", scope);
            context.getAuthenticationSession().setAuthNote("code_challenge", code_challenge);
            context.getAuthenticationSession().setAuthNote("code_challenge_method", code_challenge_method);

            if (loginHint != null) {
                formData.add(AuthenticationManager.FORM_USERNAME, loginHint);
                var inputData = new MultivaluedMapImpl<String, String>();
                inputData.add(AuthenticationManager.FORM_USERNAME, loginHint);
                if (validateUser(context, inputData)) {
                    context.success();
                    return;
                }
            } else if (rememberMeUsername != null) {
                formData.add(AuthenticationManager.FORM_USERNAME, rememberMeUsername);
                formData.add("rememberMe", "on");
            }
        }
        var challengeResponse = challenge(context, formData);
        context.challenge(challengeResponse);
    }

    @Override
    protected boolean validateForm(AuthenticationFlowContext context, MultivaluedMap<String, String> formData) {
        return validateUser(context, formData);
    }

    @Override
    protected Response challenge(AuthenticationFlowContext context, MultivaluedMap<String, String> formData) {
        var forms = context.form();

        if (!formData.isEmpty())
            forms.setFormData(formData);

        return forms.createLoginUsername();
    }

    @Override
    protected String getDefaultChallengeMessage(AuthenticationFlowContext context) {
        if (context.getRealm().isLoginWithEmailAllowed())
            return Messages.INVALID_USERNAME_OR_EMAIL;
        return Messages.INVALID_USERNAME;
    }

    public boolean validateUser(AuthenticationFlowContext context, MultivaluedMap<String, String> inputData) {
        UserModel user = getUser(context, inputData);
        return user != null && validateUser(context, user, inputData);
    }

    private boolean validateUser(AuthenticationFlowContext context, UserModel user,
            MultivaluedMap<String, String> inputData) {
        if (!enabledUser(context, user)) {
            return false;
        }
        String rememberMe = inputData.getFirst("rememberMe");
        boolean remember = context.getRealm().isRememberMe() && rememberMe != null
                && rememberMe.equalsIgnoreCase("on");
        if (remember) {
            context.getAuthenticationSession().setAuthNote(Details.REMEMBER_ME, "true");
            context.getEvent().detail(Details.REMEMBER_ME, "true");
        } else {
            context.getAuthenticationSession().removeAuthNote(Details.REMEMBER_ME);
        }
        context.setUser(user);
        return true;
    }

    private UserModel getUser(AuthenticationFlowContext context, MultivaluedMap<String, String> inputData) {
        if (isUserAlreadySetBeforeUsernamePasswordAuth(context)) {
            // Get user from the authentication context in case he was already set before
            // this authenticator
            UserModel user = context.getUser();
            testInvalidUser(context, user);
            return user;
        } else {
            // Normal login. In this case this authenticator is supposed to establish
            // identity of the user from the provided username
            context.clearUser();
            return getUserFromForm(context, inputData);
        }
    }

    private UserModel getUserFromForm(AuthenticationFlowContext context, MultivaluedMap<String, String> inputData) {
        var username = inputData.getFirst(AuthenticationManager.FORM_USERNAME);
        if (username == null) {
            context.getEvent().error(Errors.USER_NOT_FOUND);
            Response challengeResponse = challenge(context, getDefaultChallengeMessage(context), FIELD_USERNAME);
            context.failureChallenge(AuthenticationFlowError.INVALID_USER, challengeResponse);
            return null;
        }

        username = username.trim();

        var hostRealmIdp = resolver.resolve(username);
        if (hostRealmIdp != null && !(hostRealmIdp.realm().equals(context.getRealm().getName())
                && context.getUriInfo().getRequestUri().toString().startsWith(hostRealmIdp.host()))) {
            var responseMode = context.getAuthenticationSession().getAuthNote("response_mode");
            var redirectUri = URI.create(context.getAuthenticationSession().getRedirectUri());
            var redirectUriBuilder = UriBuilder.fromUri(redirectUri);
            if ("fragment".equals(responseMode)) {
                var f = redirectUri.getRawFragment();
                if (f == null || !f.contains("kc_instance="+hostRealmIdp.encode())) {
                    redirectUriBuilder.fragment((f == null ? "" : f + "&") + "kc_instance=" + hostRealmIdp.encode());
                }
            } else {
                redirectUriBuilder.queryParam("kc_instance", hostRealmIdp.encode());
            }
            var locationBuilder = UriBuilder.newInstance()
                    .scheme(hostRealmIdp.host().startsWith("https") ? "https" : "http")
                    .host(hostRealmIdp.host().replace("https://", "").replace("http://", ""))
                    .path("realms/" + hostRealmIdp.realm() + "/protocol/openid-connect/auth")
                    .queryParam("client_id", context.getAuthenticationSession().getClient().getClientId())
                    .queryParam("redirect_uri", redirectUriBuilder.build().toString())
                    .queryParam("state", context.getAuthenticationSession().getAuthNote("state"))
                    .queryParam("nonce", context.getAuthenticationSession().getAuthNote("nonce"))
                    .queryParam("response_mode", responseMode)
                    .queryParam("response_type", context.getAuthenticationSession().getAuthNote("response_type"))
                    .queryParam("scope", context.getAuthenticationSession().getAuthNote("scope"))
                    .queryParam("code_challenge", context.getAuthenticationSession().getAuthNote("code_challenge"))
                    .queryParam("code_challenge_method",
                            context.getAuthenticationSession().getAuthNote("code_challenge_method"))
                    .queryParam("login_hint", username);

            if (hostRealmIdp.idp() != null)
                locationBuilder.queryParam("kc_idp_hint", hostRealmIdp.idp());

            ServicesLogger.LOGGER.infov("Redirecting {0} to {1}", username, hostRealmIdp);

            context.forceChallenge(Response.seeOther(locationBuilder.build()).build());
            return null;
        }

        context.getEvent().detail(Details.USERNAME, username);
        context.getAuthenticationSession().setAuthNote(AbstractUsernameFormAuthenticator.ATTEMPTED_USERNAME,
                username);

        var user = (UserModel) null;
        try {
            user = KeycloakModelUtils.findUserByNameOrEmail(context.getSession(), context.getRealm(), username);
        } catch (ModelDuplicateException mde) {
            ServicesLogger.LOGGER.modelDuplicateException(mde);

            // Could happen during federation import
            if (mde.getDuplicateFieldName() != null && mde.getDuplicateFieldName().equals(UserModel.EMAIL)) {
                setDuplicateUserChallenge(context, Errors.EMAIL_IN_USE, Messages.EMAIL_EXISTS,
                        AuthenticationFlowError.INVALID_USER);
            } else {
                setDuplicateUserChallenge(context, Errors.USERNAME_IN_USE, Messages.USERNAME_EXISTS,
                        AuthenticationFlowError.INVALID_USER);
            }
            return user;
        }

        testInvalidUser(context, user);
        return user;
    }

    protected Response challenge(AuthenticationFlowContext context, String error, String field) {
        LoginFormsProvider form = context.form()
                .setExecution(context.getExecution().getId());
        if (error != null) {
            if (field != null) {
                form.addError(new FormMessage(field, error));
            } else {
                form.setError(error);
            }
            if (Messages.INVALID_USERNAME_OR_EMAIL.equals(error) || Messages.INVALID_USERNAME.equals(error)) {
                return form.createLoginUsername();
            }
        }
        return createLoginForm(form);
    }
}
