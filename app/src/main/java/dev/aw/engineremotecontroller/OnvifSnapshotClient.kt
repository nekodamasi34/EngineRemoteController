package dev.aw.engineremotecontroller

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import org.w3c.dom.Element
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory

/** Small bounded ONVIF Media1 client. No discovery multicast, global Authenticator or RTSP session. */
class OnvifSnapshotClient(private val config: CameraConfig) {
    private var snapshot: URL? = null
    private fun safeUrl(value: String): URL = URL(value).also {
        require(it.protocol in listOf("http","https") && it.host == config.host && it.userInfo == null && it.ref == null) { "カメラが別ホストのURLを返しました" }
    }
    private fun xml(s: String) = s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;")
    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes,Base64.NO_WRAP)
    private fun security(): String {
        val nonce=ByteArray(16).also { SecureRandom().nextBytes(it) }; val created=Instant.now().toString()
        val digest=b64(MessageDigest.getInstance("SHA-1").digest(nonce+created.toByteArray()+config.password.toByteArray(Charsets.UTF_8)))
        return """<wsse:Security xmlns:wsse="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd" xmlns:wsu="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd"><wsse:UsernameToken><wsse:Username>${xml(config.username)}</wsse:Username><wsse:Password Type="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest">$digest</wsse:Password><wsse:Nonce EncodingType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary">${b64(nonce)}</wsse:Nonce><wsu:Created>$created</wsu:Created></wsse:UsernameToken></wsse:Security>"""
    }
    private fun soap(url: URL, namespace: String, action: String, content: String = ""): org.w3c.dom.Document {
        val bytes="""<s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"><s:Header>${security()}</s:Header><s:Body><m:$action xmlns:m="$namespace">$content</m:$action></s:Body></s:Envelope>""".toByteArray(Charsets.UTF_8)
        val raw=request(url,bytes,"application/soap+xml; charset=utf-8; action=\"$namespace/$action\"",512*1024)
        // Android's DOM factory does not consistently support Xerces security features.
        // Parse with its native pull parser and reject DTDs before constructing a DOM.
        val parser=android.util.Xml.newPullParser()
        parser.setFeature(org.xmlpull.v1.XmlPullParser.FEATURE_PROCESS_DOCDECL,false)
        parser.setInput(raw.inputStream(),null)
        val document=DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()
        val stack=java.util.ArrayDeque<Element>(); var nodes=0
        while(true) {
            when(parser.nextToken()) {
                org.xmlpull.v1.XmlPullParser.END_DOCUMENT -> break
                org.xmlpull.v1.XmlPullParser.DOCDECL -> throw IOException("DTDは許可されていません")
                org.xmlpull.v1.XmlPullParser.START_TAG -> {
                    if(stack.size>=64 || ++nodes>10000) throw IOException("XMLが複雑すぎます")
                    val element=document.createElementNS(parser.namespace,parser.name)
                    for(i in 0 until parser.attributeCount) element.setAttributeNS(parser.getAttributeNamespace(i),parser.getAttributeName(i),parser.getAttributeValue(i))
                    if(stack.isEmpty()) document.appendChild(element) else stack.peekLast()!!.appendChild(element)
                    stack.addLast(element)
                }
                org.xmlpull.v1.XmlPullParser.END_TAG -> stack.removeLast()
                org.xmlpull.v1.XmlPullParser.TEXT, org.xmlpull.v1.XmlPullParser.CDSECT, org.xmlpull.v1.XmlPullParser.ENTITY_REF -> {
                    val text=parser.text ?: throw IOException("未定義のXML参照")
                    if(!stack.isEmpty()) stack.peekLast()!!.appendChild(document.createTextNode(text))
                }
            }
        }
        if(document.getElementsByTagNameNS("*","Fault").length>0) throw IOException("ONVIFが要求を拒否しました（認証・時刻・機能対応を確認）")
        return document
    }

    private fun discover(): URL {
        val device=safeUrl("http://${config.host}:${config.port}/onvif/device_service")
        val caps=soap(device,"http://www.onvif.org/ver10/device/wsdl","GetCapabilities","<m:Category>Media</m:Category>")
        val media=caps.getElementsByTagNameNS("*","Media").item(0) as? Element ?: throw IOException("ONVIF Media1非対応です")
        val address=media.getElementsByTagNameNS("*","XAddr").item(0)?.textContent ?: throw IOException("Media URLがありません")
        val endpoint=safeUrl(address)
        val profiles=soap(endpoint,"http://www.onvif.org/ver10/media/wsdl","GetProfiles")
        val profile=profiles.getElementsByTagNameNS("*","Profiles").item(0) as? Element ?: throw IOException("プロファイルがありません")
        val token=profile.getAttribute("token"); require(token.isNotBlank())
        val result=soap(endpoint,"http://www.onvif.org/ver10/media/wsdl","GetSnapshotUri","<m:ProfileToken>${xml(token)}</m:ProfileToken>")
        return safeUrl(result.getElementsByTagNameNS("*","Uri").item(0)?.textContent ?: throw IOException("GetSnapshotUri非対応です"))
    }
    fun capture(): ByteArray {
        config.validate()
        try {
            val url=snapshot ?: discover().also { snapshot=it }
            val raw=request(url,null,null,4*1024*1024)
            val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }; BitmapFactory.decodeByteArray(raw,0,raw.size,bounds)
            require(bounds.outWidth>0 && bounds.outHeight>0 && bounds.outWidth.toLong()*bounds.outHeight<=32_000_000) { "画像サイズが不正です" }
            val options=BitmapFactory.Options().apply { var sample=1; while(bounds.outWidth/sample>1280 || bounds.outHeight/sample>720) sample*=2; inSampleSize=sample }
            val source=BitmapFactory.decodeByteArray(raw,0,raw.size,options) ?: throw IOException("JPEGを取得できません")
            val ratio=minOf(1.0,640.0/source.width,360.0/source.height)
            val scaled=Bitmap.createScaledBitmap(source,maxOf(1,(source.width*ratio).toInt()),maxOf(1,(source.height*ratio).toInt()),true)
            try {
                var result=ByteArray(0)
                for(quality in listOf(70,55,40,25)) { result=ByteArrayOutputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG,quality,it);it.toByteArray() }; if(result.size<=50*1024) break }
                require(result.size<=128*1024) { "圧縮画像が大きすぎます" }; return result
            } finally { scaled.recycle(); if(source!==scaled) source.recycle() }
        } catch(e: Exception) { snapshot=null; throw e }
    }
    private fun request(url: URL, payload: ByteArray?, type: String?, limit: Int): ByteArray {
        var authorization: String?=null
        repeat(2) { attempt ->
            val c=url.openConnection() as HttpURLConnection
            try {
                c.instanceFollowRedirects=false; c.connectTimeout=2500; c.readTimeout=2500
                c.requestMethod=if(payload==null) "GET" else "POST"
                authorization?.let { c.setRequestProperty("Authorization",it) }
                if(payload!=null) { c.doOutput=true; c.setRequestProperty("Content-Type",type); c.setFixedLengthStreamingMode(payload.size); c.outputStream.use { it.write(payload) } }
                if(c.responseCode==401 && attempt==0) { authorization=authenticate(c.getHeaderField("WWW-Authenticate") ?: "",c.requestMethod,url); return@repeat }
                if(c.responseCode!=200) throw IOException("カメラHTTP ${c.responseCode}")
                if(c.contentLengthLong>limit) throw IOException("カメラ応答が大きすぎます")
                val deadline=android.os.SystemClock.elapsedRealtime()+5000
                return c.inputStream.use { input -> ByteArrayOutputStream().use { output ->
                    val buffer=ByteArray(8192)
                    while(true) { if(android.os.SystemClock.elapsedRealtime()>deadline || Thread.currentThread().isInterrupted) throw IOException("カメラ取得期限切れ")
                        val n=input.read(buffer); if(n<0) break; if(output.size()+n>limit) throw IOException("カメラ応答が大きすぎます"); output.write(buffer,0,n) }
                    output.toByteArray()
                } }
            } finally { c.disconnect() }
        }
        throw IOException("カメラ認証に失敗しました")
    }
    private fun authenticate(challenge: String, method: String, url: URL): String {
        if(challenge.startsWith("Basic",true)) return "Basic "+b64("${config.username}:${config.password}".toByteArray(Charsets.UTF_8))
        require(challenge.startsWith("Digest",true)) { "未対応のHTTP認証方式です" }
        val fields=Regex("(\\w+)\\s*=\\s*(?:\"([^\"]*)\"|([^,\\s]+))").findAll(challenge).associate { it.groupValues[1].lowercase() to it.groupValues[2].ifEmpty { it.groupValues[3] } }
        val realm=fields["realm"] ?: error("Digest realmなし"); val nonce=fields["nonce"] ?: error("Digest nonceなし")
        val algorithm=fields["algorithm"] ?: "MD5"; val hashName=when(algorithm.uppercase()) { "MD5","MD5-SESS" -> "MD5"; "SHA-256","SHA-256-SESS" -> "SHA-256"; else -> error("未対応のDigest algorithm") }
        fun hash(s:String)=MessageDigest.getInstance(hashName).digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
        fun quote(s:String)="\""+s.replace("\\","\\\\").replace("\"","\\\"")+"\""
        val cnonce=java.util.UUID.randomUUID().toString().replace("-","")
        val uri=url.file.ifEmpty { "/" }; val qop=fields["qop"]
        require(qop==null || qop.split(',').any { it.trim()=="auth" }) { "Digest auth-intは未対応です" }
        var a1=hash("${config.username}:$realm:${config.password}"); if(algorithm.endsWith("-sess",true)) a1=hash("$a1:$nonce:$cnonce")
        val response=hash("$a1:$nonce:"+(if(qop!=null) "00000001:$cnonce:auth:" else "")+hash("$method:$uri"))
        return "Digest username=${quote(config.username)}, realm=${quote(realm)}, nonce=${quote(nonce)}, uri=${quote(uri)}, response=${quote(response)}, algorithm=$algorithm"+
            (fields["opaque"]?.let { ", opaque=${quote(it)}" } ?: "")+(if(qop!=null) ", qop=auth, nc=00000001, cnonce=${quote(cnonce)}" else if(algorithm.endsWith("-sess",true)) ", cnonce=${quote(cnonce)}" else "")
    }
}
