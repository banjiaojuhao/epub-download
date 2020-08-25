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
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.net.URI
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream


data class DownloadItem(val href: String, val type: String)
data class ResourceItem(val path: String, val content: Buffer)

fun main(args: Array<String>) = runBlocking<Unit> {
    val httpOptions = webClientOptionsOf(
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/76.0.3809.100 Safari/537.36",
            trustAll = true,
            followRedirects = true,
            verifyHost = false,
            keepAlive = true,
            tryUseCompression = true)
    val defaultFiles = listOf(
            ResourceItem("mimetype", Buffer.buffer("application/epub+zip")),
            ResourceItem("META-INF/container.xml", Buffer.buffer("<?xml version=\"1.0\"?>\n" +
                    "<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">\n" +
                    "   <rootfiles>\n" +
                    "      <rootfile full-path=\"content.opf\" media-type=\"application/oebps-package+xml\"/>\n" +
                    "   </rootfiles>\n" +
                    "</container>"))
    )

    val vertx = Vertx.vertx()
    val webClient = WebClient.create(vertx, httpOptions)!!
    val fileSystem = vertx.fileSystem()

    val url = "http://reader.epubee.com/books/mobile/5f/5f80cfe69440056dc623f051c2f76246/"
    val bookId = url.substringAfter("mobile/").substringAfter("/").substringBefore("/")
    val cachePath = Path.of("cache")
    val bookPath = cachePath.resolve(bookId)

    val bookPathStr = bookPath.toString()
    if (!fileSystem.existsAwait(bookPathStr)) {
        fileSystem.mkdirAwait(bookPathStr)
    }

    val contents = downloadIndex(bookPath, fileSystem, webClient, url)

    // parse contents to get resources list
    val dom = Jsoup.parse(contents.toString(), url, Parser.xmlParser())
    val title = dom.select("package > metadata > dc|title").text()
    val author = dom.select("package > metadata > dc|creator").text()

    val downloadList = dom.select("package > manifest > item").map {
        DownloadItem(it.attr("href"), it.attr("media-type"))
    }
    val totalSize = downloadList.size
    val resourceList = downloadList.mapIndexed { index, item ->
        val content = downloadResources(item, bookPath, fileSystem, webClient, url)
        println("progress: ${
            (100 * (index + 1).toDouble() / totalSize.toDouble())
                    .toString()
                    .substringBefore(".")
        }%")
        ResourceItem(item.href, content)
    }

    val epubPath = cachePath.resolve("$title - $author.epub").toString()
    FileOutputStream(epubPath).use {
        BufferedOutputStream(it).use {
            ZipOutputStream(it).use {
                it.setLevel(9)

                it.putNextEntry(ZipEntry("content.opf"))
                it.write(contents.bytes)
                for (item in defaultFiles) {
                    it.putNextEntry(ZipEntry(item.path))
                    it.write(item.content.bytes)
                }
                for (item in resourceList) {
                    it.putNextEntry(ZipEntry(item.path))
                    it.write(item.content.bytes)
                }
                it.flush()
            }
        }
    }
    println("epub downloaded to $epubPath")

    webClient.close()
    vertx.closeAwait()
}

private suspend fun downloadIndex(rootPath: Path, fileSystem: FileSystem, webClient: WebClient, url: String): Buffer {
    val pathOPF = rootPath.resolve("content.opf").toString()
    return if (!fileSystem.existsAwait(pathOPF)) {
        val response = webClient.getAbs(url + "content.opf")
                .sendAwait()
        val contentBuffer = response.bodyAsBuffer()
        fileSystem.writeFileAwait(pathOPF, contentBuffer)
        contentBuffer
    } else {
        fileSystem.readFileAwait(pathOPF)
    }
}

private suspend fun downloadResources(item: DownloadItem, rootPath: Path, fileSystem: FileSystem, webClient: WebClient, url: String): Buffer {
    val path = rootPath.resolve(item.href).toString()
    val content = if (!fileSystem.existsAwait(path)) {
        val itemBuffer = webClient.getAbs(URI(url).resolve(item.href).toString())
                .sendAwait()
                .bodyAsBuffer()
        fileSystem.writeFileAwait(path, itemBuffer)
        println("${item.href} downloaded")
        itemBuffer
    } else {
        println("${item.href} already exists")
        fileSystem.readFileAwait(path)
    }

    return if (item.type == "application/xhtml+xml") {
        val parse = Jsoup.parse(content.toString())
        val contentHtml = parse.select(".readercontent-inner")
        if (contentHtml.isNotEmpty()) {
            parse.select("body").html(contentHtml.html())
            val newHtml = parse.html()
            fileSystem.writeFileAwait(path, Buffer.buffer(newHtml))
            Buffer.buffer(newHtml)
        } else {
            content
        }
    } else {
        content
    }
}
