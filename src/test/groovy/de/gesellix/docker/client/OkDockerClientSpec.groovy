package de.gesellix.docker.client

import de.gesellix.docker.client.protocolhandler.contenthandler.RawHeaderAndPayload
import de.gesellix.docker.client.protocolhandler.contenthandler.RawInputStream
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import org.apache.commons.io.IOUtils
import org.codehaus.groovy.runtime.MethodClosure
import spock.lang.IgnoreIf
import spock.lang.Specification
import spock.lang.Unroll

import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory

import static de.gesellix.docker.client.protocolhandler.contenthandler.StreamType.STDOUT

class OkDockerClientSpec extends Specification {

    @IgnoreIf({ System.env.DOCKER_HOST })
    def "getProtocolAndHost should fallback to unix:///var/run/docker.sock (npipe on Windows)"() {
        given:
        def client = new OkDockerClient()
        def isWindows = System.properties['os.name'].toLowerCase().contains('windows')
        def expectedScheme = isWindows ? "npipe" : "unix"
        def defaultHost = isWindows ? "//./pipe/docker_engine" : "/var/run/docker.sock"

        expect:
        client.getProtocolAndHost() == [expectedScheme, defaultHost, -1]
    }

    @IgnoreIf({ System.env.DOCKER_HOST })
    def "getProtocolAndHost should use to docker.host system property when set"() {
        given:
        def oldDockerHost = System.setProperty("docker.host", "http://127.0.0.1:2375")
        def client = new OkDockerClient()
        expect:
        client.getProtocolAndHost() == ["http", "127.0.0.1", 2375]
        cleanup:
        if (oldDockerHost) {
            System.setProperty("docker.host", oldDockerHost)
        } else {
            System.clearProperty("docker.host")
        }
    }

    def "getProtocolAndHost should support tcp protocol"() {
        def client = new OkDockerClient(
                config: new DockerConfig(
                        dockerHost: "tcp://127.0.0.1:2375"))
        when:
        def (protocol, host, port) = client.getProtocolAndHost()
        then:
        protocol =~ /https?/
        and:
        host == "127.0.0.1"
        and:
        port == 2375
    }

    def "getProtocolAndHost should support tls port"() {
        given:
        def certsPath = IOUtils.getResource("/certs").file
        def oldDockerCertPath = System.setProperty("docker.cert.path", certsPath)
        def client = new OkDockerClient(
                config: new DockerConfig(
                        dockerHost: "tcp://127.0.0.1:2376"))
        when:
        def (protocol, host, port) = client.getProtocolAndHost()
        then:
        protocol == "https"
        and:
        host == "127.0.0.1"
        and:
        port == 2376
        cleanup:
        if (oldDockerCertPath) {
            System.setProperty("docker.cert.path", oldDockerCertPath)
        } else {
            System.clearProperty("docker.cert.path")
        }
    }

    def "getProtocolAndHost should support https protocol"() {
        given:
        def certsPath = IOUtils.getResource("/certs").file
        def oldDockerCertPath = System.setProperty("docker.cert.path", certsPath)
        def client = new OkDockerClient(
                config: new DockerConfig(
                        dockerHost: "https://127.0.0.1:2376"))
        expect:
        client.getProtocolAndHost() == ["https", "127.0.0.1", 2376]
        cleanup:
        if (oldDockerCertPath) {
            System.setProperty("docker.cert.path", oldDockerCertPath)
        } else {
            System.clearProperty("docker.cert.path")
        }
    }

    def "getMimeType"() {
        def client = new OkDockerClient(
                config: new DockerConfig(
                        dockerHost: "https://127.0.0.1:2376"))
        expect:
        client.getMimeType(contentType) == expectedMimeType
        where:
        contentType                         | expectedMimeType
        null                                | null
        "application/json"                  | "application/json"
        "text/plain"                        | "text/plain"
        "text/plain; charset=utf-8"         | "text/plain"
        "text/html"                         | "text/html"
        "application/vnd.docker.raw-stream" | "application/vnd.docker.raw-stream"
    }

    def "getCharset"() {
        def client = new OkDockerClient(
                config: new DockerConfig(
                        dockerHost: "https://127.0.0.1:2376"))
        expect:
        client.getCharset(contentType) == expectedCharset
        where:
        contentType                         | expectedCharset
        "application/json"                  | "utf-8"
        "text/plain"                        | "utf-8"
        "text/plain; charset=ISO-8859-1"    | "ISO-8859-1"
        "text/html"                         | "utf-8"
        "application/vnd.docker.raw-stream" | "utf-8"
    }

    def "queryToString"() {
        def client = new OkDockerClient(
                config: new DockerConfig(
                        dockerHost: "https://127.0.0.1:2376"))
        expect:
        client.queryToString(parameters) == query
        where:
        parameters                           | query
        null                                 | ""
        [:]                                  | ""
        [param1: "value-1"]                  | "param1=value-1"
        ["p 1": "v 1"]                       | "p+1=v+1"
        [param1: "v 1", p2: "v-2"]           | "param1=v+1&p2=v-2"
        [params: ["v 1", "v-2"]]             | "params=v+1&params=v-2"
        [params: ["a 1", "a-2"] as String[]] | "params=a+1&params=a-2"
    }

    @Unroll
    def "generic request with bad config: #requestConfig"() {
        def client = new OkDockerClient(
                config: new DockerConfig(
                        dockerHost: "https://127.0.0.1:2376"))
        when:
        client.request(requestConfig as Map)
        then:
        def ex = thrown(RuntimeException)
        ex.message == "bad request config"
        where:
        requestConfig << [null, [:], ["foo": "bar"]]
    }

    @Unroll
    def "#method request with bad config: #requestConfig"() {
        def client = new OkDockerClient(
                config: new DockerConfig(
                        dockerHost: "https://127.0.0.1:2376"))
        when:
        new MethodClosure(client, method).call(requestConfig as Map)
        then:
        def ex = thrown(RuntimeException)
        ex.message == "bad request config"
        where:
        requestConfig  | method
        null           | "get"
        null           | "post"
        [:]            | "get"
        [:]            | "post"
        ["foo": "bar"] | "get"
        ["foo": "bar"] | "post"
    }

    def "head request uses the HEAD method"() {
        def client = new OkDockerClient(
                config: new DockerConfig(
                        dockerHost: "https://127.0.0.1:2376"))
        given:
        client.metaClass.request = { Map config ->
            config.method
        }
        when:
        def method = client.head("/foo")
        then:
        method == "HEAD"
    }

    def "get request uses the GET method"() {
        def client = new OkDockerClient(
                config: new DockerConfig(
                        dockerHost: "https://127.0.0.1:2376"))
        given:
        client.metaClass.request = { Map config ->
            config.method
        }
        when:
        def method = client.get("/foo")
        then:
        method == "GET"
    }

    def "put request uses the PUT method"() {
        def client = new OkDockerClient(
                config: new DockerConfig(
                        dockerHost: "https://127.0.0.1:2376"))
        given:
        client.metaClass.request = { Map config ->
            config.method
        }
        when:
        def method = client.put("/foo")
        then:
        method == "PUT"
    }

    def "post request uses the POST method"() {
        def client = new OkDockerClient(
                config: new DockerConfig(
                        dockerHost: "https://127.0.0.1:2376"))
        given:
        client.metaClass.request = { Map config ->
            config.method
        }
        when:
        def method = client.post("/foo")
        then:
        method == "POST"
    }

    def "delete request uses the DELETE method"() {
        def client = new OkDockerClient(
                config: new DockerConfig(
                        dockerHost: "https://127.0.0.1:2376"))
        given:
        client.metaClass.request = { Map config ->
            config.method
        }
        when:
        def method = client.delete("/foo")
        then:
        method == "DELETE"
    }

    def "getWebsocketClient creates a websocket client"() {
        given:
        def certsPath = IOUtils.getResource("/certs").file
        def oldDockerCertPath = System.setProperty("docker.cert.path", certsPath)
        def client = new OkDockerClient(
                config: new DockerConfig(
                        dockerHost: "https://127.0.0.1:2376"))

        when:
        def wsClient = client.getWebsocketClient("/foo", Mock(DefaultWebsocketHandler))

        then:
        wsClient != null

        cleanup:
        if (oldDockerCertPath) {
            System.setProperty("docker.cert.path", oldDockerCertPath)
        } else {
            System.clearProperty("docker.cert.path")
        }
    }

    def "openConnection uses DIRECT proxy by default"() {
        given:
        def httpServer = new TestHttpServer()
        def serverAddress = httpServer.start()
        def client = new OkDockerClient(
                config: new DockerConfig(
                        dockerHost: "http://127.0.0.1:${serverAddress.port}",
                        tlsVerify: 0))
        when:
        def connection = client.openConnection([method: "GET",
                                                path  : "/foo"])
        connection.connect()
        then:
        !connection.usingProxy()
        cleanup:
        httpServer.stop()
    }

    def "openConnection uses configured proxy"() {
        given:
        def httpServer = new TestHttpServer()
        def proxyAddress = httpServer.start()
        def proxy = new Proxy(Proxy.Type.HTTP, proxyAddress)
        def client = new OkDockerClient(
                config: new DockerConfig(
                        dockerHost: "http://any.thi.ng:4711",
                        tlsVerify: 0),
                proxy: proxy)
        when:
        def connection = client.openConnection([method: "GET",
                                                path  : "/test/"])
        connection.connect()
        then:
        connection.usingProxy()
        cleanup:
        httpServer.stop()
    }

    def "openConnection with path"() {
        def client = new OkDockerClient(
                config: new DockerConfig(
                        dockerHost: "http://127.0.0.1:2375"))
        when:
        def connection = client.openConnection([method: "GET",
                                                path  : "/foo"])
        then:
        connection.URL.protocol =~ /https?/
        and:
        connection.URL.host == "127.0.0.1"
        and:
        connection.URL.port == 2375
        and:
        connection.URL.file == "/foo"
    }

    def "openConnection with path and query"() {
        def client = new OkDockerClient(
                config: new DockerConfig(
                        dockerHost: "http://127.0.0.1:2375"))
        when:
        def connection = client.openConnection([method: "GET",
                                                path  : "/bar",
                                                query : [baz: "la/la", answer: 42]])
        then:
        connection.URL.protocol =~ /https?/
        and:
        connection.URL.host == "127.0.0.1"
        and:
        connection.URL.port == 2375
        and:
        connection.URL.file == "/bar?baz=la%2Fla&answer=42"
    }

    def "configureConnection with plain http connection"() {
        def client = new OkDockerClient(
                config: new DockerConfig(
                        dockerHost: "http://127.0.0.1:2375"))
        def connectionMock = Mock(HttpURLConnection)
        when:
        client.configureConnection(connectionMock, [method: "HEADER"])
        then:
        1 * connectionMock.setRequestMethod("HEADER")
    }

    def "configureConnection with https connection"() {
        given:
        def certsPath = IOUtils.getResource("/certs").file
        def oldDockerCertPath = System.setProperty("docker.cert.path", certsPath)
        def client = new OkDockerClient(
                config: new DockerConfig(
                        dockerHost: "https://127.0.0.1:2376"))
        def connectionMock = Mock(HttpsURLConnection)

        when:
        client.configureConnection(connectionMock, [method: "HEADER"])

        then:
        1 * connectionMock.setRequestMethod("HEADER")
        and:
        1 * connectionMock.setSSLSocketFactory(_ as SSLSocketFactory)

        cleanup:
        if (oldDockerCertPath) {
            System.setProperty("docker.cert.path", oldDockerCertPath)
        } else {
            System.clearProperty("docker.cert.path")
        }
    }

    def "request should return statusLine"() {
        given:
        def client = new OkDockerClient(
                config: new DockerConfig(
                        dockerHost: "http://127.0.0.1:2375"))
        def connectionMock = Mock(HttpURLConnection)
        client.metaClass.openConnection = {
            connectionMock
        }
        def headerFields = [:]
        headerFields[null] = [statusLine]
        connectionMock.getHeaderFields() >> headerFields
        connectionMock.responseCode >> statusCode

        when:
        def response = client.request([method: "OPTIONS",
                                       path  : "/a-resource"])

        then:
        response.status == expectedStatusLine

        where:
        statusLine                           | statusCode | expectedStatusLine
        "HTTP/1.1 100 Continue"              | 100        | [text: ["HTTP/1.1 100 Continue"], code: 100, success: false]
        "HTTP/1.1 200 OK"                    | 200        | [text: ["HTTP/1.1 200 OK"], code: 200, success: true]
        "HTTP/1.1 204 No Content"            | 204        | [text: ["HTTP/1.1 204 No Content"], code: 204, success: true]
        "HTTP/1.1 302 Found"                 | 302        | [text: ["HTTP/1.1 302 Found"], code: 302, success: false]
        "HTTP/1.1 404 Not Found"             | 404        | [text: ["HTTP/1.1 404 Not Found"], code: 404, success: false]
        "HTTP/1.1 500 Internal Server Error" | 500        | [text: ["HTTP/1.1 500 Internal Server Error"], code: 500, success: false]
    }

    def "request should return headers"() {
        given:
        def mediaType = MediaType.parse("text/plain")
        def responseBody = "holy ship"
        def client = new OkDockerClient() {
            @Override
            OkHttpClient newClient(OkHttpClient.Builder clientBuilder) {
                clientBuilder
                        .addInterceptor(new ConstantResponseInterceptor(ResponseBody.create(mediaType, responseBody)))
                        .build()
            }
        }
        client.config = new DockerConfig(dockerHost: "http://127.0.0.1:2375")
//        headerFields["Content-Type"] = ["text/plain;encoding=utf-8"]
//        headerFields["Content-Length"] = ["${"123456789".length()}"]
//        connectionMock.inputStream >> new ByteArrayInputStream("123456789".bytes)

        when:
        def response = client.request([method: "HEADER",
                                       path  : "/a-resource"])

        then:
        response.headers == ['content-type'  : ["text/plain; encoding=utf-8"],
                             'content-length': ["9"]]
        and:
        response.contentType == "text/plain;encoding=utf-8"
        and:
        response.mimeType == "text/plain"
        and:
        response.contentLength == "9"
    }

    def "request should return consumed content"() {
        given:
        def mediaType = MediaType.parse("text/plain")
        def responseBody = "holy ship"
        def client = new OkDockerClient() {
            @Override
            OkHttpClient newClient(OkHttpClient.Builder clientBuilder) {
                clientBuilder
                        .addInterceptor(new ConstantResponseInterceptor(ResponseBody.create(mediaType, responseBody)))
                        .build()
            }
        }
        client.config = new DockerConfig(dockerHost: "http://127.0.0.1:2375")

        when:
        def response = client.request([method: "HEADER",
                                       path  : "/a-resource"])

        then:
        response.stream == null
        and:
        response.content == "holy ship"
    }

    def "request with stdout stream and known content length"() {
        given:
        def mediaType = MediaType.parse("text/html")
        def responseBody = "holy ship"
        def client = new OkDockerClient() {
            @Override
            OkHttpClient newClient(OkHttpClient.Builder clientBuilder) {
                clientBuilder
                        .addInterceptor(new ConstantResponseInterceptor(ResponseBody.create(mediaType, responseBody)))
                        .build()
            }
        }
        client.config = new DockerConfig(dockerHost: "http://127.0.0.1:2375")
        def stdout = new ByteArrayOutputStream()

        when:
        def response = client.request([method: "HEADER",
                                       path  : "/a-resource",
                                       stdout: stdout])

        then:
        stdout.toByteArray() == "holy ship".bytes
        and:
        response.stream == null
    }

    def "request with stdout stream and unknown content length"() {
        given:
        def mediaType = MediaType.parse("text/html")
        def responseBody = "holy ship"
        def client = new OkDockerClient() {
            @Override
            OkHttpClient newClient(OkHttpClient.Builder clientBuilder) {
                clientBuilder
                        .addInterceptor(new ConstantResponseInterceptor(ResponseBody.create(mediaType, -1, new Buffer().write(responseBody.bytes))))
                        .build()
            }
        }
        client.config = new DockerConfig(dockerHost: "http://127.0.0.1:2375")

        when:
        def response = client.request([method: "HEADER",
                                       path  : "/a-resource"])

        then:
        response.stream.available() == "holy ship".length()
        and:
        IOUtils.toByteArray(response.stream as InputStream) == "holy ship".bytes
    }

    def "request with unknown mime type"() {
        given:
        def mediaType = MediaType.parse("unknown/mime-type")
        def responseBody = "holy ship"
        def client = new OkDockerClient() {
            @Override
            OkHttpClient newClient(OkHttpClient.Builder clientBuilder) {
                clientBuilder
                        .addInterceptor(new ConstantResponseInterceptor(ResponseBody.create(mediaType, -1, new Buffer().write(responseBody.bytes))))
                        .build()
            }
        }
        client.config = new DockerConfig(dockerHost: "http://127.0.0.1:2375")

        when:
        def response = client.request([method: "HEADER",
                                       path  : "/a-resource"])

        then:
        response.stream.available() == "holy ship".length()
        and:
        IOUtils.toByteArray(response.stream as InputStream) == "holy ship".bytes
    }

    def "request with unknown mime type and stdout"() {
        given:
        def mediaType = MediaType.parse("unknown/mime-type")
        def responseBody = "holy ship"
        def client = new OkDockerClient() {
            @Override
            OkHttpClient newClient(OkHttpClient.Builder clientBuilder) {
                clientBuilder
                        .addInterceptor(new ConstantResponseInterceptor(ResponseBody.create(mediaType, -1, new Buffer().write(responseBody.bytes))))
                        .build()
            }
        }
        client.config = new DockerConfig(dockerHost: "http://127.0.0.1:2375")
        def stdout = new ByteArrayOutputStream()

        when:
        def response = client.request([method: "HEADER",
                                       path  : "/a-resource",
                                       stdout: stdout])

        then:
        stdout.toByteArray() == "holy ship".bytes
        and:
        response.stream == null
    }

    def "request with json response"() {
        given:
        def mediaType = MediaType.parse("application/json")
        def responseBody = '{"holy":"ship"}'
        def client = new OkDockerClient() {
            @Override
            OkHttpClient newClient(OkHttpClient.Builder clientBuilder) {
                clientBuilder
                        .addInterceptor(new ConstantResponseInterceptor(ResponseBody.create(mediaType, responseBody)))
                        .build()
            }
        }
        client.config = new DockerConfig(dockerHost: "http://127.0.0.1:2375")

        when:
        def response = client.request([method: "HEADER",
                                       path  : "/a-resource"])

        then:
        response.stream == null
        and:
        response.content == [holy: 'ship']
    }

    def "request with docker raw-stream response"() {
        given:
        def actualText = "holy ship"
        def headerAndPayload = new RawHeaderAndPayload(STDOUT, actualText.bytes)
        def responseBody = new ByteArrayInputStream((byte[]) headerAndPayload.bytes)
        def mediaType = MediaType.parse("application/vnd.docker.raw-stream")
        def client = new OkDockerClient() {
            @Override
            OkHttpClient newClient(OkHttpClient.Builder clientBuilder) {
                clientBuilder
                        .addInterceptor(new ConstantResponseInterceptor(ResponseBody.create(mediaType, responseBody.bytes)))
                        .build()
            }
        }
        client.config = new DockerConfig(dockerHost: "http://127.0.0.1:2375")

        when:
        def response = client.request([method: "HEADER",
                                       path  : "/a-resource"])

        then:
        response.stream instanceof RawInputStream
        and:
        IOUtils.toByteArray(response.stream as RawInputStream) == actualText.bytes
        and:
        response.content == null
    }

    def "request with docker raw-stream response on stdout"() {
        given:
        def actualText = "holy ship"
        def headerAndPayload = new RawHeaderAndPayload(STDOUT, actualText.bytes)
        def responseBody = new ByteArrayInputStream((byte[]) headerAndPayload.bytes)
        def mediaType = MediaType.parse("application/vnd.docker.raw-stream")
        def client = new OkDockerClient() {
            @Override
            OkHttpClient newClient(OkHttpClient.Builder clientBuilder) {
                clientBuilder
                        .addInterceptor(new ConstantResponseInterceptor(ResponseBody.create(mediaType, responseBody.bytes)))
                        .build()
            }
        }
        client.config = new DockerConfig(dockerHost: "https://127.0.0.1:2376")
        client.dockerSslSocketFactory = new DockerSslSocketFactory() {
            @Override
            def createDockerSslSocket(String certPath) {
                return null
            }
        }
        def stdout = new ByteArrayOutputStream()

        when:
        def response = client.request([method: "HEADER",
                                       path  : "/a-resource",
                                       stdout: stdout])

        then:
        stdout.toByteArray() == actualText.bytes
        and:
        responseBody.available() == 0
        and:
        response.stream == null
        and:
        response.content == null
    }

    static class TestContentHandlerFactory implements ContentHandlerFactory {

        def contentHandler = null

        @Override
        ContentHandler createContentHandler(String mimetype) {
            return contentHandler
        }
    }

    static class TestContentHandler extends ContentHandler {

        def result = null

        @Override
        Object getContent(URLConnection urlc) throws IOException {
            return result
        }
    }

    static class ConstantResponseInterceptor implements Interceptor {
        def ResponseBody responseBody

        ConstantResponseInterceptor(ResponseBody responseBody) {
            this.responseBody = responseBody
        }

        @Override
        Response intercept(Interceptor.Chain chain) throws IOException {
            new Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(responseBody)
                    .addHeader("Content-Type", responseBody.contentType().toString())
                    .addHeader("Content-Length", Long.toString(responseBody.contentLength()))
                    .build()
        }
    }
}
