/*
 * Copyright (C) 2013 tarent AG
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY
 * CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT,
 * TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package org.osiam.web.controller;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.inject.Inject;
import javax.mail.MessagingException;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.io.IOUtils;
import org.osiam.client.exception.OsiamClientException;
import org.osiam.client.exception.OsiamRequestException;
import org.osiam.helper.ObjectMapperWithExtensionConfig;
import org.osiam.resources.scim.Email;
import org.osiam.resources.scim.Extension;
import org.osiam.resources.scim.ExtensionFieldType;
import org.osiam.resources.scim.Role;
import org.osiam.resources.scim.UpdateUser;
import org.osiam.resources.scim.User;
import org.osiam.web.exception.OsiamException;
import org.osiam.web.service.ConnectorBuilder;
import org.osiam.web.service.RegistrationExtensionUrnProvider;
import org.osiam.web.template.RenderAndSendEmail;
import org.osiam.web.util.RegistrationHelper;
import org.osiam.web.util.SimpleAccessToken;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;

import com.google.common.base.Optional;

/**
 * Controller to handle the registration process
 * 
 */
@Controller
@RequestMapping(value = "/register")
public class RegisterController {

    private static final Logger LOGGER = Logger.getLogger(RegisterController.class.getName());

    @Inject
    private ObjectMapperWithExtensionConfig mapper;

    @Inject
    private RegistrationExtensionUrnProvider registrationExtensionUrnProvider;

    @Inject
    private ServletContext context;

    @Inject
    private RenderAndSendEmail renderAndSendEmailService;

    @Inject
    private ConnectorBuilder connectorBuilder;

    /* Registration email configuration */
    @Value("${osiam.mail.register.linkprefix}")
    private String registermailLinkPrefix;
    @Value("${osiam.mail.from}")
    private String fromAddress;

    /* Registration extension configuration */
    @Value("${osiam.scim.extension.field.activationtoken}")
    private String activationTokenField;

    /* URI for the registration call from JavaScript */
    @Value("${osiam.html.register.url}")
    private String clientRegistrationUri;

    // css and js libs
    @Value("${osiam.html.dependencies.bootstrap}")
    private String bootStrapLib;

    @Value("${osiam.html.dependencies.angular}")
    private String angularLib;

    @Value("${osiam.html.dependencies.jquery}")
    private String jqueryLib;

    /**
     * Generates a HTTP form with the fields for registration purpose.
     */
    @RequestMapping(method = RequestMethod.GET)
    public void index(HttpServletResponse response) throws IOException {
        response.setContentType("text/html");

        InputStream inputStream = context.getResourceAsStream("/WEB-INF/registration/registration.html");

        // replace registration link
        String htmlContent = IOUtils.toString(inputStream, "UTF-8");
        String replacedHtmlContent = htmlContent.replace("$REGISTERLINK", clientRegistrationUri);

        // replace all libs
        replacedHtmlContent = replacedHtmlContent.replace("$BOOTSTRAP", bootStrapLib);
        replacedHtmlContent = replacedHtmlContent.replace("$ANGULAR", angularLib);

        InputStream in = IOUtils.toInputStream(replacedHtmlContent);

        IOUtils.copy(in, response.getOutputStream());
    }

    /**
     * Creates a new User.
     * 
     * Needs all data given by the 'index'-form. Saves the user in an inactivate-state. Sends an activation-email to the
     * registered email-address.
     * 
     * @param authorization
     *        a valid access token
     * @return the saved user and HTTP.OK (200) for successful creation, otherwise only the HTTP status
     * @throws IOException
     * @throws MessagingException
     */
    @RequestMapping(value = "/create", method = RequestMethod.POST, produces = "application/json")
    public ResponseEntity<String> create(@RequestHeader final String token, @RequestBody String user)
            throws IOException, MessagingException {

        User parsedUser = mapper.readValue(user, User.class);

        Optional<Email> email = RegistrationHelper.extractSendToEmail(parsedUser);
        if (!email.isPresent()) {
            LOGGER.log(Level.WARNING, "Could not register user. No email of user " + parsedUser.getUserName()
                    + " found!");
            return new ResponseEntity<>("{\"error\":\"Could not register user. No email of user "
                    + parsedUser.getUserName() + " found!\"}", HttpStatus.BAD_REQUEST);
        }

        // generate Activation Token
        String activationToken = UUID.randomUUID().toString();
        User createUser = createUserForRegistration(parsedUser, activationToken);

        User createdUser;
        try {
            createdUser = connectorBuilder.createConnector().createUser(createUser, new SimpleAccessToken(token));
        } catch (OsiamRequestException e) {
            LOGGER.log(Level.WARNING, e.getMessage());
            return new ResponseEntity<>("{\"error\":\"" + e.getMessage() + "\"}",
                    HttpStatus.valueOf(e.getHttpStatusCode()));
        } catch (OsiamClientException e) {
            return new ResponseEntity<>("{\"error\":\"" + e.getMessage() + "\"}",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }

        String registrationLink = RegistrationHelper.createLinkForEmail(registermailLinkPrefix, createdUser.getId(),
                "activationToken", activationToken);

        Map<String, String> mailVariables = new HashMap<>();
        mailVariables.put("registerlink", registrationLink);

        try {
            renderAndSendEmailService.renderAndSendEmail("registration", fromAddress, email.get().getValue(),
                    createdUser,
                    mailVariables);
        } catch (OsiamException e) {
            return new ResponseEntity<>("{\"error\":\"Problems creating email for user registration: \""
                    + e.getMessage() + "}", HttpStatus.INTERNAL_SERVER_ERROR);
        }

        return new ResponseEntity<>(mapper.writeValueAsString(createdUser), HttpStatus.OK);
    }

    /**
     * Activates a previously registered user.
     * 
     * After activation E-Mail arrived the activation link will point to this URI.
     * 
     * @param authorization
     *        an valid OAuth2 token
     * @param userId
     *        the id of the registered user
     * @param token
     *        the user's activation token, send by E-Mail
     * 
     * @return HTTP status, HTTP.OK (200) for a valid activation
     */
    @RequestMapping(value = "/activate", method = RequestMethod.POST, produces = "application/json")
    public ResponseEntity<String> activate(@RequestHeader final String authorization,
            @RequestParam final String userId, @RequestParam final String token) throws IOException {

        if (token.equals("")) {
            LOGGER.log(Level.WARNING, "Activation token miss match!");
            return new ResponseEntity<>("{\"error\":\"Activation token miss match!\"}", HttpStatus.UNAUTHORIZED);
        }

        SimpleAccessToken accessToken = new SimpleAccessToken(token);
        try {
            User user = connectorBuilder.createConnector().getCurrentUser(accessToken);

            Extension extension = user.getExtension(registrationExtensionUrnProvider.getExtensionUrn());
            String activationTokenFieldValue = extension.getField(activationTokenField, ExtensionFieldType.STRING);

            if (!activationTokenFieldValue.equals(token)) {
                LOGGER.log(Level.WARNING, "Activation token miss match!");
                return new ResponseEntity<>("{\"error\":\"Activation token miss match!\"}", HttpStatus.UNAUTHORIZED);
            }

            UpdateUser updateUser = getPreparedUserForActivation(extension);
            connectorBuilder.createConnector().updateUser(userId, updateUser, accessToken);
        } catch (OsiamRequestException e) {
            LOGGER.log(Level.WARNING, e.getMessage());
            return new ResponseEntity<>("{\"error\":\"" + e.getMessage() + "\"}",
                    HttpStatus.valueOf(e.getHttpStatusCode()));
        } catch (OsiamClientException e) {
            return new ResponseEntity<>("{\"error\":\"" + e.getMessage() + "\"}",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return new ResponseEntity<String>(HttpStatus.OK);
    }

    private UpdateUser getPreparedUserForActivation(Extension extension) {
        UpdateUser updateUser = new UpdateUser.Builder()
                .deleteExtensionField(extension.getUrn(), activationTokenField)
                .updateActive(true).build();

        return updateUser;
    }

    private User createUserForRegistration(User parsedUser, String activationToken) {
        Extension extension = new Extension(registrationExtensionUrnProvider.getExtensionUrn());
        extension.addOrUpdateField(activationTokenField, activationToken);
        List<Role> roles = new ArrayList<Role>();
        Role role = new Role.Builder().setValue("USER").build();
        roles.add(role);

        User completeUser = new User.Builder(parsedUser)
                .setActive(false)
                .setRoles(roles)
                .addExtension(extension)
                .build();

        return completeUser;
    }
}