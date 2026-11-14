package uk.jj.pdfplus.sign

import com.artifex.mupdf.fitz.FitzInputStream
import com.artifex.mupdf.fitz.PKCS7DistinguishedName
import com.artifex.mupdf.fitz.PKCS7Signer
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x500.style.IETFUtils
import org.bouncycastle.cert.jcajce.JcaCertStore
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import uk.jj.pdfplus.App
import java.io.File
import java.io.InputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date

/** A certificate-based digital ID kept in the app's private storage as a PKCS#12 file. */
object DigitalId {
    private fun file() = File(App.instance.filesDir, "digital-id.p12")
    fun exists() = file().exists()
    fun delete() { file().delete() }

    class Loaded(val key: PrivateKey, val chain: List<X509Certificate>) {
        val cert: X509Certificate get() = chain[0]
        private fun part(id: org.bouncycastle.asn1.ASN1ObjectIdentifier): String =
            runCatching { X500Name.getInstance(cert.subjectX500Principal.encoded).getRDNs(id).firstOrNull()?.first?.value?.let { IETFUtils.valueToString(it) } }.getOrNull().orEmpty()
        val commonName: String get() = part(BCStyle.CN)
        val email: String get() = part(BCStyle.E)
        val organisation: String get() = part(BCStyle.O)
        val selfSigned: Boolean get() = cert.subjectX500Principal == cert.issuerX500Principal
    }

    fun load(password: String): Loaded? = runCatching { read(file().inputStream(), password) }.getOrNull()

    private fun read(input: InputStream, password: String): Loaded {
        val ks = KeyStore.getInstance("PKCS12")
        input.use { ks.load(it, password.toCharArray()) }
        val alias = ks.aliases().toList().first { ks.isKeyEntry(it) }
        val key = ks.getKey(alias, password.toCharArray()) as PrivateKey
        val chain = (ks.getCertificateChain(alias) ?: arrayOf(ks.getCertificate(alias))).map { it as X509Certificate }
        return Loaded(key, chain)
    }

    /** Imports a .p12 / .pfx file supplied by the user. Returns false if the password is wrong or the file has no key. */
    fun import(bytes: ByteArray, password: String): Boolean = runCatching {
        read(bytes.inputStream(), password)
        file().writeBytes(bytes); true
    }.getOrDefault(false)

    /** Creates a self-signed ID. Other people's readers will show it as "not verified" until they trust it. */
    fun create(name: String, email: String, organisation: String, password: String): Boolean = runCatching {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val nb = X500NameBuilder(BCStyle.INSTANCE).addRDN(BCStyle.CN, name)
        if (organisation.isNotBlank()) nb.addRDN(BCStyle.O, organisation)
        if (email.isNotBlank()) nb.addRDN(BCStyle.E, email)
        val subject = nb.build()
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(subject, BigInteger(64, SecureRandom()), Date(now - 86_400_000L), Date(now + 5L * 365 * 86_400_000L), subject, kp.public)
        val holder = builder.build(JcaContentSignerBuilder("SHA256withRSA").build(kp.private))
        val cert = JcaX509CertificateConverter().getCertificate(holder)
        val ks = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        ks.setKeyEntry("pdfplus", kp.private, password.toCharArray(), arrayOf(cert))
        file().outputStream().use { ks.store(it, password.toCharArray()) }
        true
    }.getOrDefault(false)

    fun signer(id: Loaded): PKCS7Signer = object : PKCS7Signer() {
        override fun name(): PKCS7DistinguishedName = PKCS7DistinguishedName().apply {
            cn = id.commonName; o = id.organisation; email = id.email; ou = ""; c = ""
        }

        override fun sign(stm: FitzInputStream): ByteArray {
            val data = stm.readBytes()
            val algorithm = if (id.key.algorithm.equals("EC", true)) "SHA256withECDSA" else "SHA256withRSA"
            val gen = CMSSignedDataGenerator()
            val contentSigner = JcaContentSignerBuilder(algorithm).build(id.key)
            gen.addSignerInfoGenerator(JcaSignerInfoGeneratorBuilder(JcaDigestCalculatorProviderBuilder().build()).build(contentSigner, id.cert))
            gen.addCertificates(JcaCertStore(id.chain))
            return gen.generate(CMSProcessableByteArray(data), false).encoded
        }

        override fun maxDigest(): Int = 16384
    }
}
