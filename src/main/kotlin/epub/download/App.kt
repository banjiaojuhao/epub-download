package epub.download

import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.file.FileSystem
import io.vertx.ext.web.client.WebClient
import io.vertx.kotlin.core.closeAwait
import io.vertx.kotlin.core.file.existsAwait
import io.vertx.kotlin.core.file.mkdirAwait
import io.vertx.kotlin.core.file.readFileAwait
import io.vertx.kotlin.core.file.writeFileAwait
import io.vertx.kotlin.ext.web.client.sendAwait
import io.vertx.kotlin.ext.web.client.webClientOptionsOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.net.URI
import java.nio.file.Path


data class Item(val href: String, val type: String)


fun main(args: Array<String>) = runBlocking<Unit> {
    val httpOptions = webClientOptionsOf(
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/76.0.3809.100 Safari/537.36",
            trustAll = true,
            followRedirects = true,
            verifyHost = false,
            keepAlive = true,
            tryUseCompression = true)

    val vertx = Vertx.vertx()
    val webClient = WebClient.create(vertx, httpOptions)!!
    val fileSystem = vertx.fileSystem()

    val url = "http://reader.epubee.com/books/mobile/5f/5f80cfe69440056dc623f051c2f76246/"
    val bookId = url.substringAfter("mobile/").substringAfter("/").substringBefore("/")
    val rootPath = Path.of("cache", bookId)

    val rootPathStr = rootPath.toString()
    if (!fileSystem.existsAwait(rootPathStr)) {
        fileSystem.mkdirAwait(rootPathStr)
    }

    // generate META-INF/container.xml and mimetype flies
    launch {
        touchDefaultFiles(rootPath, fileSystem)
    }


    val contents = downloadContents(rootPath, fileSystem, webClient, url)

    // parse contents to get resources list
    val dom = Jsoup.parse(contents, url, Parser.xmlParser())
    val resourceList = dom.select("package > manifest > item").map {
        Item(it.attr("href"), it.attr("media-type"))
    }

    downloadResources(resourceList, rootPath, fileSystem, webClient, url)



    webClient.close()
    vertx.closeAwait()
}

private suspend fun touchDefaultFiles(rootPath: Path, fileSystem: FileSystem) {
    val containerDirPath = rootPath.resolve("META-INF")
    val containerPath = containerDirPath.resolve("container.xml").toString()
    val mimetypePath = rootPath.resolve("mimetype").toString()

    val containerDirPathStr = containerDirPath.toString()
    if (!fileSystem.existsAwait(containerDirPathStr)) {
        fileSystem.mkdirAwait(containerDirPathStr)
    }
    if (!fileSystem.existsAwait(containerPath)) {
        fileSystem.writeFileAwait(containerPath,
                Buffer.buffer("<?xml version=\"1.0\"?>\n" +
                        "<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">\n" +
                        "   <rootfiles>\n" +
                        "      <rootfile full-path=\"content.opf\" media-type=\"application/oebps-package+xml\"/>\n" +
                        "   </rootfiles>\n" +
                        "</container>"))
    }
    if (!fileSystem.existsAwait(mimetypePath)) {
        fileSystem.writeFileAwait(mimetypePath, Buffer.buffer("application/epub+zip"))
    }
}

private suspend fun downloadContents(rootPath: Path, fileSystem: FileSystem, webClient: WebClient, url: String): String {
    val pathOPF = rootPath.resolve("content.opf").toString()

    return if (!fileSystem.existsAwait(pathOPF)) {
        val response = webClient.getAbs(url + "content.opf")
                .sendAwait()
        val contentBuffer = response.bodyAsBuffer()
        val content = contentBuffer.toString()
        fileSystem.writeFileAwait(pathOPF, contentBuffer)
        content
    } else {
        fileSystem.readFileAwait(pathOPF).toString()
    }
}

private suspend fun downloadResources(resourceList: List<Item>, rootPath: Path, fileSystem: FileSystem, webClient: WebClient, url: String) {
    for (index in resourceList.indices) {
        val item = resourceList[index]
        println("${index / resourceList.size}\t${item.href}")
        val path = rootPath.resolve(item.href).toString()
        val html = if (!fileSystem.existsAwait(path)) {
            val itemBuffer = webClient.getAbs(URI(url).resolve(item.href).toString())
                    .sendAwait()
                    .bodyAsBuffer()
            fileSystem.writeFileAwait(path, itemBuffer)
            itemBuffer
        } else {
            fileSystem.readFileAwait(path)
        }

        if (item.type == "application/xhtml+xml") {
            val parse = Jsoup.parse(html.toString())
            val contentHtml = parse.select(".readercontent-inner")
            if (contentHtml.isNotEmpty()) {
                parse.select("body").html(contentHtml.html())
                val newHtml = parse.html()
                fileSystem.writeFileAwait(path, Buffer.buffer(newHtml))
            }
        }
    }
}
