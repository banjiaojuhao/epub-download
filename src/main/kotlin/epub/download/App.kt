package epub.download

import io.vertx.core.Vertx
import io.vertx.ext.web.client.WebClient
import io.vertx.kotlin.core.closeAwait
import io.vertx.kotlin.ext.web.client.sendAwait
import io.vertx.kotlin.ext.web.client.webClientOptionsOf
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.mapdb.DB
import org.mapdb.DBMaker
import org.mapdb.HTreeMap
import org.mapdb.Serializer
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.net.URL
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream


data class DownloadItem(val href: String, val type: String)
data class ResourceItem(val path: String, val content: ByteArray)

private val epubPath = Path.of("epub")

fun main(args: Array<String>) = runBlocking<Unit> {
    val db = DBMaker.fileDB("cache.mapdb").make()
    val cache = db.hashMap("cache", Serializer.STRING, Serializer.BYTE_ARRAY).createOrOpen()

    val httpOptions = webClientOptionsOf(
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/76.0.3809.100 Safari/537.36",
            trustAll = true,
            followRedirects = true,
            verifyHost = false,
            keepAlive = true,
            tryUseCompression = true)
    val defaultFiles = listOf(
            ResourceItem("mimetype", "application/epub+zip".toByteArray()),
            ResourceItem("META-INF/container.xml", ("<?xml version=\"1.0\"?>\n" +
                    "<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">\n" +
                    "   <rootfiles>\n" +
                    "      <rootfile full-path=\"content.opf\" media-type=\"application/oebps-package+xml\"/>\n" +
                    "   </rootfiles>\n" +
                    "</container>").toByteArray())
    )

    val vertx = Vertx.vertx()
    val webClient = WebClient.create(vertx, httpOptions)!!
    val epubDirFile = epubPath.toFile()
    if (!epubDirFile.isDirectory) {
        epubDirFile.mkdir()
    }

    if (args.isEmpty()) {
        // interactive mode
        while (true) {
            println("please input url like: http://reader.epubee.com/books/mobile/5f/5f80cfe69440056dc623f051c2f76246/")
            println("q to quit")
            val url = readLine()?.trim() ?: break
            if (url == "q") {
                break
            }
            downloadEpub(URL(url), db, cache, webClient, defaultFiles)
        }
    } else {
        // url from args
        for (url in args) {
            downloadEpub(URL(url), db, cache, webClient, defaultFiles)
        }
    }

    db.close()
    webClient.close()
    vertx.closeAwait()
}

private suspend fun downloadEpub(url: URL, db: DB, cache: HTreeMap<String, ByteArray>, webClient: WebClient, defaultFiles: List<ResourceItem>) {
    val bookId = url.toString().substringAfter("mobile/").substringAfter("/").substringBefore("/")
    val bookPath = Path.of(bookId)

    val contents = downloadIndex(bookPath, db, cache, webClient, url)

    // parse contents to get resources list
    val dom = Jsoup.parse(contents.toString(Charsets.UTF_8), url.toString(), Parser.xmlParser())
    val title = dom.select("package > metadata > dc|title").text()
    val author = dom.select("package > metadata > dc|creator").text()

    val downloadList = dom.select("package > manifest > item").map {
        DownloadItem(it.attr("href"), it.attr("media-type"))
    }
    val totalSize = downloadList.size
    val resourceList = downloadList.mapIndexed { index, item ->
        val content = downloadResources(item, bookPath, db, cache, webClient, url)
        println("progress: ${
            (100 * (index + 1).toDouble() / totalSize.toDouble())
                    .toString()
                    .substringBefore(".")
        }%")
        ResourceItem(item.href, content)
    }

    val epubPath = epubPath.resolve("$title - $author.epub").toString()
    FileOutputStream(epubPath).use {
        BufferedOutputStream(it).use {
            ZipOutputStream(it).use {
                it.setLevel(9)

                it.putNextEntry(ZipEntry("content.opf"))
                it.write(contents)
                for (item in defaultFiles) {
                    it.putNextEntry(ZipEntry(item.path))
                    it.write(item.content)
                }
                for (item in resourceList) {
                    it.putNextEntry(ZipEntry(item.path))
                    it.write(item.content)
                }
                it.flush()
            }
        }
    }
    println("epub downloaded to $epubPath")
}

private suspend fun downloadIndex(rootPath: Path, db: DB, cache: HTreeMap<String, ByteArray>, webClient: WebClient, url: URL): ByteArray {
    val pathOPF = rootPath.resolve("content.opf").toString()
    return if (!cache.containsKey(pathOPF)) {
        val response = webClient.getAbs(URL(url, "content.opf").toString())
                .sendAwait()
        val contentBuffer = response.bodyAsBuffer().bytes
        cache[pathOPF] = contentBuffer
        db.commit()
        contentBuffer
    } else {
        cache[pathOPF]!!
    }
}

private suspend fun downloadResources(item: DownloadItem, rootPath: Path, db: DB, cache: HTreeMap<String, ByteArray>, webClient: WebClient, url: URL): ByteArray {
    val path = rootPath.resolve(item.href).toString()
    val content = if (!cache.containsKey(path)) {

        val itemBuffer = webClient.getAbs(URL(url, item.href).toString())
                .sendAwait()
                .bodyAsBuffer()
                .bytes
        cache[path] = itemBuffer
        db.commit()
        println("${item.href} downloaded")
        itemBuffer
    } else {
        println("${item.href} already exists")
        cache[path]!!
    }

    return if (item.type == "application/xhtml+xml") {
        val parse = Jsoup.parse(content.toString(Charsets.UTF_8))
        val contentHtml = parse.select(".readercontent-inner")
        if (contentHtml.isNotEmpty()) {
            parse.select("body").html(contentHtml.html())
            val newHtml = parse.html().toByteArray()
            cache[path] = newHtml
            db.commit()
            newHtml
        } else {
            content
        }
    } else {
        content
    }
}
