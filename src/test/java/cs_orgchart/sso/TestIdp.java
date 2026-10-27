package cs_orgchart.sso;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.opensaml.core.xml.XMLObject;
import org.opensaml.core.xml.XMLObjectBuilderFactory;
import org.opensaml.core.xml.config.XMLObjectProviderRegistrySupport;
import org.opensaml.core.xml.schema.XSString;
import org.opensaml.saml.common.SAMLVersion;
import org.opensaml.saml.saml2.core.Assertion;
import org.opensaml.saml.saml2.core.Attribute;
import org.opensaml.saml.saml2.core.AttributeStatement;
import org.opensaml.saml.saml2.core.AttributeValue;
import org.opensaml.saml.saml2.core.Audience;
import org.opensaml.saml.saml2.core.AudienceRestriction;
import org.opensaml.saml.saml2.core.AuthnContext;
import org.opensaml.saml.saml2.core.AuthnContextClassRef;
import org.opensaml.saml.saml2.core.AuthnStatement;
import org.opensaml.saml.saml2.core.Conditions;
import org.opensaml.saml.saml2.core.Issuer;
import org.opensaml.saml.saml2.core.NameID;
import org.opensaml.saml.saml2.core.Response;
import org.opensaml.saml.saml2.core.Status;
import org.opensaml.saml.saml2.core.StatusCode;
import org.opensaml.saml.saml2.core.Subject;
import org.opensaml.saml.saml2.core.SubjectConfirmation;
import org.opensaml.saml.saml2.core.SubjectConfirmationData;
import org.opensaml.security.x509.BasicX509Credential;
import org.opensaml.xmlsec.signature.Signature;
import org.opensaml.xmlsec.signature.support.SignatureConstants;
import org.opensaml.xmlsec.signature.support.Signer;
import org.springframework.security.saml2.core.OpenSamlInitializationService;
import org.w3c.dom.Element;

import javax.xml.namespace.QName;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A made-up identity provider for the tests, signing as Microsoft Entra ID signs (the assertion, RSA-SHA256).
 * Its key and certificate are made at run time and its metadata is written to a temporary file that stands for
 * SAML_METADATA_URI: no key is stored in the repository.
 */
public final class TestIdp {

    public static final String SITE = "https://orgchart.test";
    public static final String ENTITY_ID = SITE + "/saml/orgchart";
    public static final String ACS_PATH = "/login/saml2/sso/entra";
    public static final String ACS_URL = SITE + ACS_PATH;
    public static final String SIGN_ON_PATH = "/saml2/authenticate/entra";
    public static final String IDP_ENTITY_ID = "https://idp.orgchart.test/tests-only";
    public static final String IDP_SSO_URL = IDP_ENTITY_ID + "/saml2";

    public static final String EMAIL_CLAIM = "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/emailaddress";
    public static final String NAME_CLAIM = "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/name";
    public static final String OBJECT_ID_CLAIM = "http://schemas.microsoft.com/identity/claims/objectidentifier";
    public static final String GIVEN_NAME_CLAIM = "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/givenname";

    private static final BasicX509Credential CREDENTIAL;
    private static final String METADATA_URI;

    static {
        OpenSamlInitializationService.initialize();
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keys = generator.generateKeyPair();
            X500Name name = new X500Name("CN=OrgChart test identity provider");
            Instant now = Instant.now();
            X509Certificate certificate = new JcaX509CertificateConverter().getCertificate(
                    new JcaX509v3CertificateBuilder(name, BigInteger.valueOf(now.toEpochMilli()),
                            Date.from(now.minus(Duration.ofDays(1))), Date.from(now.plus(Duration.ofDays(30))),
                            name, keys.getPublic())
                            .build(new JcaContentSignerBuilder("SHA256withRSA").build(keys.getPrivate())));
            CREDENTIAL = new BasicX509Credential(certificate, keys.getPrivate());
            Path metadata = Files.createTempFile("orgchart-test-idp", ".xml");
            metadata.toFile().deleteOnExit();
            Files.writeString(metadata, metadata(certificate), StandardCharsets.UTF_8);
            METADATA_URI = metadata.toUri().toString();
        } catch (Exception ex) {
            throw new IllegalStateException("cannot make the test identity provider", ex);
        }
    }

    private TestIdp() {
    }

    /** The file: address of the identity provider's metadata (SAML_METADATA_URI of the tests). */
    public static String metadataUri() {
        return METADATA_URI;
    }

    /** The person as Microsoft would describe them: the NameID (null: none) and the claims (null values left out). */
    public record Person(String nameId, Map<String, String> claims) {

        /** As Entra sends a colleague: NameID, e-mail, UPN (claim "name"), object id, given name. */
        public static Person of(String email) {
            Map<String, String> claims = new LinkedHashMap<>();
            claims.put(EMAIL_CLAIM, email);
            claims.put(NAME_CLAIM, email);
            claims.put(OBJECT_ID_CLAIM, UUID.randomUUID().toString());
            claims.put(GIVEN_NAME_CLAIM, "Test");
            return new Person(email, claims);
        }

        public static Person withoutClaims(String nameId) {
            return new Person(nameId, Map.of());
        }
    }

    /** A signed, base64 answer to the request {@code inResponseTo}, for the audience of the tests. */
    public static String signed(Person person, String inResponseTo) {
        return signed(person, inResponseTo, ENTITY_ID);
    }

    public static String signed(Person person, String inResponseTo, String audience) {
        Instant now = Instant.now();
        Response response = build(Response.DEFAULT_ELEMENT_NAME);
        response.setID("_" + UUID.randomUUID());
        response.setIssueInstant(now);
        response.setVersion(SAMLVersion.VERSION_20);
        response.setDestination(ACS_URL);
        response.setInResponseTo(inResponseTo);
        response.setIssuer(issuer());
        Status status = build(Status.DEFAULT_ELEMENT_NAME);
        StatusCode code = build(StatusCode.DEFAULT_ELEMENT_NAME);
        code.setValue(StatusCode.SUCCESS);
        status.setStatusCode(code);
        response.setStatus(status);

        Assertion assertion = build(Assertion.DEFAULT_ELEMENT_NAME);
        assertion.setID("_" + UUID.randomUUID());
        assertion.setIssueInstant(now);
        assertion.setVersion(SAMLVersion.VERSION_20);
        assertion.setIssuer(issuer());
        assertion.setSubject(subject(person, inResponseTo, now));
        assertion.setConditions(conditions(audience, now));
        assertion.getAuthnStatements().add(authnStatement(now));
        if (!person.claims().isEmpty()) {
            assertion.getAttributeStatements().add(attributes(person.claims()));
        }
        response.getAssertions().add(assertion);

        Signature signature = build(Signature.DEFAULT_ELEMENT_NAME);
        signature.setSigningCredential(CREDENTIAL);
        signature.setSignatureAlgorithm(SignatureConstants.ALGO_ID_SIGNATURE_RSA_SHA256);
        signature.setCanonicalizationAlgorithm(SignatureConstants.ALGO_ID_C14N_EXCL_OMIT_COMMENTS);
        assertion.setSignature(signature);
        try {
            Element element = XMLObjectProviderRegistrySupport.getMarshallerFactory()
                    .getMarshaller(response).marshall(response);
            Signer.signObject(signature);
            return Base64.getEncoder().encodeToString(serialize(element).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("cannot sign the test answer", ex);
        }
    }

    private static String metadata(X509Certificate certificate) throws Exception {
        String pem = Base64.getEncoder().encodeToString(certificate.getEncoded());
        return """
                <md:EntityDescriptor xmlns:md="urn:oasis:names:tc:SAML:2.0:metadata" entityID="%s">
                  <md:IDPSSODescriptor protocolSupportEnumeration="urn:oasis:names:tc:SAML:2.0:protocol">
                    <md:KeyDescriptor use="signing">
                      <ds:KeyInfo xmlns:ds="http://www.w3.org/2000/09/xmldsig#">
                        <ds:X509Data><ds:X509Certificate>%s</ds:X509Certificate></ds:X509Data>
                      </ds:KeyInfo>
                    </md:KeyDescriptor>
                    <md:SingleSignOnService Binding="urn:oasis:names:tc:SAML:2.0:bindings:HTTP-Redirect" Location="%s"/>
                  </md:IDPSSODescriptor>
                </md:EntityDescriptor>
                """.formatted(IDP_ENTITY_ID, pem, IDP_SSO_URL);
    }

    private static Issuer issuer() {
        Issuer issuer = build(Issuer.DEFAULT_ELEMENT_NAME);
        issuer.setValue(IDP_ENTITY_ID);
        return issuer;
    }

    private static Subject subject(Person person, String inResponseTo, Instant now) {
        Subject subject = build(Subject.DEFAULT_ELEMENT_NAME);
        if (person.nameId() != null) {
            NameID nameId = build(NameID.DEFAULT_ELEMENT_NAME);
            nameId.setValue(person.nameId());
            nameId.setFormat(NameID.EMAIL);
            subject.setNameID(nameId);
        }
        SubjectConfirmation confirmation = build(SubjectConfirmation.DEFAULT_ELEMENT_NAME);
        confirmation.setMethod(SubjectConfirmation.METHOD_BEARER);
        SubjectConfirmationData data = build(SubjectConfirmationData.DEFAULT_ELEMENT_NAME);
        data.setRecipient(ACS_URL);
        data.setNotOnOrAfter(now.plus(Duration.ofMinutes(5)));
        data.setInResponseTo(inResponseTo);
        confirmation.setSubjectConfirmationData(data);
        subject.getSubjectConfirmations().add(confirmation);
        return subject;
    }

    private static Conditions conditions(String audienceUri, Instant now) {
        Conditions conditions = build(Conditions.DEFAULT_ELEMENT_NAME);
        conditions.setNotBefore(now.minus(Duration.ofMinutes(1)));
        conditions.setNotOnOrAfter(now.plus(Duration.ofMinutes(5)));
        AudienceRestriction restriction = build(AudienceRestriction.DEFAULT_ELEMENT_NAME);
        Audience audience = build(Audience.DEFAULT_ELEMENT_NAME);
        audience.setURI(audienceUri);
        restriction.getAudiences().add(audience);
        conditions.getAudienceRestrictions().add(restriction);
        return conditions;
    }

    private static AuthnStatement authnStatement(Instant now) {
        AuthnStatement statement = build(AuthnStatement.DEFAULT_ELEMENT_NAME);
        statement.setAuthnInstant(now);
        statement.setSessionIndex("_session-" + UUID.randomUUID());
        AuthnContext context = build(AuthnContext.DEFAULT_ELEMENT_NAME);
        AuthnContextClassRef classRef = build(AuthnContextClassRef.DEFAULT_ELEMENT_NAME);
        classRef.setURI(AuthnContext.PPT_AUTHN_CTX);
        context.setAuthnContextClassRef(classRef);
        statement.setAuthnContext(context);
        return statement;
    }

    private static AttributeStatement attributes(Map<String, String> claims) {
        AttributeStatement statement = build(AttributeStatement.DEFAULT_ELEMENT_NAME);
        claims.forEach((name, value) -> {
            if (value == null) {
                return;
            }
            Attribute attribute = build(Attribute.DEFAULT_ELEMENT_NAME);
            attribute.setName(name);
            XSString text = (XSString) XMLObjectProviderRegistrySupport.getBuilderFactory()
                    .getBuilder(XSString.TYPE_NAME).buildObject(AttributeValue.DEFAULT_ELEMENT_NAME, XSString.TYPE_NAME);
            text.setValue(value);
            attribute.getAttributeValues().add(text);
            statement.getAttributes().add(attribute);
        });
        return statement;
    }

    @SuppressWarnings("unchecked")
    private static <T extends XMLObject> T build(QName name) {
        XMLObjectBuilderFactory factory = XMLObjectProviderRegistrySupport.getBuilderFactory();
        return (T) factory.getBuilder(name).buildObject(name);
    }

    private static String serialize(Element element) throws Exception {
        var transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        StringWriter out = new StringWriter();
        transformer.transform(new DOMSource(element), new StreamResult(out));
        return out.toString();
    }
}
