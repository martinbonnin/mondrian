#!/usr/bin/env kscript

@file:DependsOn("com.squareup.okio:okio:2.4.3")
@file:DependsOn("com.github.ajalt:clikt:2.5.0")
@file:DependsOn("com.squareup.moshi:moshi:1.9.2")
@file:DependsOn("com.squareup.okhttp3:okhttp:4.4.1")

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.long
import com.squareup.moshi.Moshi
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.internal.indexOfLastNonAsciiWhitespace
import okio.buffer
import okio.source
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import java.io.File
import java.io.FileWriter
import java.util.*
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

fun fetch(name: String, url: String, force: Boolean): File {
    File("tmp").mkdirs()
    val file = File("tmp/$name")
    if (file.exists() && !force) {
        return file
    }

    println("Downloading... $url")
    Request.Builder().url(url).build().let {
        OkHttpClient().newCall(it).execute()
    }.let {
        val body = it.body
        if (body == null || !it.isSuccessful) {
            throw Exception("cannot get $url: $it")
        }
        body.byteStream().use { inputStream ->
            file.outputStream().use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        }
    }

    return file
}

fun Any.unsafe(key: String) = (this as Map<String, Any>).get(key)
fun Any.unsafeString(key: String) = unsafe(key) as String
fun Any.unsafeNumber(key: String) = unsafe(key) as Number
fun Any.unsafeObject(key: String) = unsafe(key) as Map<String, Any>
fun Any.unsafeList(key: String) = unsafe(key) as List<Any>

fun generateSlotToSpeakerList(speakerCount: Int, permutationCount: Long): List<List<Int>> {
    val count = permutationCount.coerceAtMost(factorial(speakerCount.toLong()))
    return (0 until permutationCount).map {
        (0 until speakerCount).shuffled()
    }
}

fun generateTemplates(name: String, sessionizeData: Any, speakerIds: List<String>, permutations: Long, virtual: Boolean, force: Boolean) {

    val speakerPairs = speakerIds.map { speakerId ->
        speakerId to sessionizeData.unsafeList("speakers").first { speaker ->
            speaker.unsafeString("id") == speakerId
        }.unsafeString("profilePicture")
    }

    // Add threading
    val avatarFiles = speakerPairs.map {
        fetch(it.first, it.second, force)
    }

    val count = avatarFiles.size

    val vString = if (virtual) "v" else ""
    val template = File("Twitter_AM/Twitter_${vString}AM_$count.svg")
    if (!template.exists()) {
        throw Exception("no template found for $count sessions")
    }

    val factory = DocumentBuilderFactory.newInstance()
    val builder = factory.newDocumentBuilder()
    val doc = builder.parse(template)

    generateSlotToSpeakerList(speakerCount = count, permutationCount = permutations).forEachIndexed { index, slotToSpeaker ->
        slotToSpeaker.forEachIndexed { slotIndex, avatarIndex ->
            val avatarFile = avatarFiles[avatarIndex]
            val attributeName = "user${slotIndex + 1}-img"
            val image = doc.documentElement.getElementsByTagName("image")
                .toList()
                .map { it as Element }
                .first {
                    it.getAttribute("id") == attributeName
                }

            image.setAttribute(
                "xlink:href",
                "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(avatarFile.readBytes())
            )
        }
        File("out").mkdirs()
        val transformer = TransformerFactory.newInstance().newTransformer()
        val writer = FileWriter(File("out/${name}_$index.svg"))
        val result = StreamResult(writer)
        transformer.transform(DOMSource(doc), result)
    }
}

fun factorial(x: Long): Long = if (x == 1L) 1L else x * factorial(x - 1)

fun NodeList.toList() = (0 until length).map { this.item(it) }

object : CliktCommand() {
    val sessionIds by argument(help = "the sessionize session ids").multiple()
    val sessionizeUrl by option(help = "the sessionize sessionUrl").required()
    val permutations by option(help = "number of permutations to do to have multiple options to choose from").long().default(10)
    val virtual by option(help = "whether to use the virtual (black) template or the regular one").flag()
    val force by option(help = "force re-downloading the pictures if they're not there already").flag()

    override fun run() {

        val json = fetch(name = "all.json", url = sessionizeUrl, force = force)
        val data = Moshi.Builder().build().adapter(Any::class.java).fromJson(json.source().buffer())

        val speakerIds = data!!.unsafeList("sessions")
            .filter { session: Any ->
                session.unsafeString("id") in sessionIds
            }.flatMap { session: Any ->
                session.unsafeList("speakers") as List<String>
            }

        generateTemplates(name = "toto",
            speakerIds = speakerIds,
            force = force,
            permutations = permutations,
            sessionizeData = data,
            virtual = virtual)
    }
}.main(args)