package epub.download

import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
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
import java.net.URI
import java.nio.file.Path


data class Item(val href: String, val type: String)


fun main(args: Array<String>) = runBlocking<Unit> {
    val url = "http://reader.epubee.com/books/mobile/5f/5f80cfe69440056dc623f051c2f76246/"
    val bookId = url.substringAfter("mobile/").substringAfter("/").substringBefore("/")
    val rootPath = Path.of("cache", bookId)

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

    if (!fileSystem.existsAwait(bookId)) {
        fileSystem.mkdirAwait(bookId)
    }


    val pathOPF = rootPath.resolve("content.opf").toString()

    val content = if (!fileSystem.existsAwait(pathOPF)) {
        val response = webClient.getAbs(url + "content.opf")
                .sendAwait()
        val contentBuffer = response.bodyAsBuffer()
        val content = contentBuffer.toString()
        fileSystem.writeFileAwait(pathOPF, contentBuffer)
        content
    } else {
        fileSystem.readFileAwait(pathOPF).toString()
    }

    val dom = Jsoup.parse(content, url, Parser.xmlParser())
    val resourceList = dom.select("package > manifest > item").map {
        Item(it.attr("href"), it.attr("media-type"))
    }

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
            val contentHtml = parse.select(".readercontent-inner").html()
            parse.select("body").html(contentHtml)
            val newHtml = parse.html()
            println()
            fileSystem.writeFileAwait(path, Buffer.buffer(newHtml))
        }
    }



    webClient.close()
    vertx.closeAwait()
}
