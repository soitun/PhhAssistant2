package me.phh.phhassistant2

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.app.Activity
import android.app.assist.AssistStructure
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.util.Log
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.Executor
import kotlin.concurrent.thread


class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val tv = TextView(this)
        tv.text = "Hello world"
        setContentView(tv)
    }
}


fun together_xyz_complete(text: String): String {
    val url = "https://api.together.xyz/inference"
    val body = JSONObject()
    body.put("max_tokens", 128)
    body.put("stream_tokens", false)
    body.put("stop", JSONArray(listOf("</s>", "[/INST]", "<|eot_id|>", "<|end|>")))
    body.put("temperature", 0.15)
    body.put("model", "meta-llama/Llama-3-70b-chat-hf")
    //body.put("model", "meta-llama/Llama-3-8b-chat-hf")
    //body.put("model", "mistralai/Mistral-7B-Instruct-v0.3")
    //body.put("model", "microsoft/Phi-3-mini-128k-instruct")
    body.put("prompt", text)

    // Do the request
    //val conn = URL(url).openConnection() as HttpsURLConnection
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.requestMethod = "POST"
    conn.setRequestProperty("Content-Type", "application/json")
    conn.setRequestProperty("Authorization", "Bearer $TOGETHER_XYZ_TOKEN")
    conn.doOutput = true

    conn.outputStream.write(body.toString().toByteArray())
    conn.outputStream.close()

    // Check the answer code
    if (conn.responseCode != 200) {
        Log.e("PHH-Voice", "Failed to get response from together.xyz: ${conn.responseCode}")
        throw Exception("Failed to get response from together.xyz")
    }

    // Read the response
    val response = conn.inputStream.bufferedReader().readText()
    val json = JSONObject(response)
    Log.e("PHH-RX", "Received json $json")
    return json.getJSONObject("output").getJSONArray("choices").getJSONObject(0).getString("text")
}
// python -m vllm.entrypoints.openai.api_server --model microsoft/Phi-3-mini-128k-instruct --dtype auto --trust-remote-code --gpu-memory-utilization 0.85 --max-model-len 40000 --kv-cache-dtype fp8
fun vllm_complete(text: String, max_tokens: Int = 128): String {
    val url = "http://192.168.1.82:8000/v1/completions"
    val body = JSONObject()
    body.put("max_tokens", max_tokens)
    body.put("temperature", 0.15)
    //body.put("model", "meta-llama/Llama-3-70b-chat-hf")
    //body.put("model", "meta-llama/Llama-3-8b-chat-hf")
    //body.put("model", "mistralai/Mistral-7B-Instruct-v0.3")
    body.put("model", "microsoft/Phi-3-mini-128k-instruct")
    body.put("prompt", text)

    // Do the request
    //val conn = URL(url).openConnection() as HttpsURLConnection
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.requestMethod = "POST"
    conn.setRequestProperty("Content-Type", "application/json")
    conn.doOutput = true

    conn.outputStream.write(body.toString().toByteArray())
    conn.outputStream.close()

    // Check the answer code
    if (conn.responseCode != 200) {
        Log.e("PHH-Voice", "Failed to get response from vllm: ${conn.responseCode} ${conn.errorStream.bufferedReader().readText()}}")
        throw Exception("Failed to get response from vllm")
    }

    // Read the response
    val response = conn.inputStream.bufferedReader().readText()
    val json = JSONObject(response)
    val choice = json.getJSONArray("choices").getJSONObject(0)
    val usage = json.getJSONObject("usage")
    Log.e("PHH-RX", "Tokens: ${usage.getLong("prompt_tokens")} + ${usage.getLong("completion_tokens")}")
    Log.e("PHH-RX", "stop reason ${choice.getString("stop_reason")}, finish reason ${choice.getString("finish_reason")}")
    return choice.getString("text")
}

fun llamacpp_complete(text: String, max_tokens: Int = 128): String {
    val url = "http://192.168.1.82:8080/completion"
    val body = JSONObject()
    body.put("max_tokens", max_tokens)
    body.put("temperature", 0.15)
    //body.put("model", "meta-llama/Llama-3-70b-chat-hf")
    //body.put("model", "meta-llama/Llama-3-8b-chat-hf")
    //body.put("model", "mistralai/Mistral-7B-Instruct-v0.3")
    body.put("prompt", text)

    // Do the request
    //val conn = URL(url).openConnection() as HttpsURLConnection
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.requestMethod = "POST"
    conn.setRequestProperty("Content-Type", "application/json")
    conn.doOutput = true

    conn.outputStream.write(body.toString().toByteArray())
    conn.outputStream.close()

    // Check the answer code
    if (conn.responseCode != 200) {
        Log.e("PHH-Voice", "Failed to get response from vllm: ${conn.responseCode} ${conn.errorStream.bufferedReader().readText()}}")
        throw Exception("Failed to get response from vllm")
    }

    // Read the response
    val response = conn.inputStream.bufferedReader().readText()
    val json = JSONObject(response)
    return json.getString("content")
}

class Conversation(val convType: ConvType = ConvType.AGENT) {
    enum class ConvType {
        DESCRIPTION,
        AGENT,
    }
    val prompt = """
You are a helpful assistant running on an Android smartphone, and you have access to the user's screen. Here's a dump of an Android Activity. Describe what's the current screen, what you're doing and why you're doing it with lines prefixed with Thoguhts:
Everything you answer is a formatted JSON, except your Thoughts.
This is a smartphone, so most of the interaction are using the touchscreen. Use DPAD only if you know where is the focus.
You might need to run multiples actions to execute a task. You will be provided with the updated view tree at every step of the way.
Wait for the view tree to update before executing the next action.
There is no global search, you must always go inside the app first.
If the task is too complicated, you can abort, and ask the user for more information.

Execute the actions the user asks you to. Here are the actions you can perform:
You can click at a position, by answering a JSON {"function":"click", "pos": [x, y]}
You can swipe up or down from center of the screen with {"function":"swipe", "direction": "up"} or {"function":"swipe", "direction": "down"}
You can type text in an input field with: {"function":"input_text", "pos":[x,y], "text", "This is something that will be typed in current text input"}
You can say something to the user with: {"function":"say", "text": "Hello world"}
You can do a global action, one of back, home, notifications, recents, dpad_up, dpad_down, dpad_left, dpad_right, dpad_center . Example: {"function":"global_action", "action": "back"}
You can launch an app just with its name with {"function":"launch_app", "app": "app name"}
Notify that you need to execute another action after the view refresh with {"continue":true}
If you think you need to wait for the view to update, you can just send {"continue":true}

There are other higher level functions you can use, like:
- Launch an intent. Example: {"function":"launch_intent", "intent": "android.intent.action.VIEW", "data": "https://news.ycombinator.com"}
- Control media. Example: {"function":"media", "action": "playpause"}, {"function":"media", "action": "seekto", "progress": 0.33}, {"function":"media", "action": "volume", "volume": 0.5}, {"function":"media", "action": "next"}
- Remember something permanently. This will be reminded at every interaction. Example: {"function":"remember_global", "message":"The user's name is Bob"}

    """.trimIndent()

    val promptEnd = """
This is the end of the examples.
""".trimIndent()

    val examples = listOf(
        listOf(
            // Show how to change a global setting
            Message("user", "Enable dark mode", Tag.USER),
            Message("system", "[...] (view tree)", Tag.TREE),
            Message("assistant", "", Tag.MODEL_START),
            Message("assistant", "Okay we're currently in a web browser. First launch the settings app", Tag.THOUGHTS),
            Message("assistant", """{"function":"launch_app", "app": "Settings"}""", Tag.ACTION),
            Message("assistant", """{"continue":true}""", Tag.ACTION),
            Message("assistant", "", Tag.MODEL_END),
            Message("system", "[...] (view tree)", Tag.TREE),
            Message("assistant", "", Tag.MODEL_START),
            Message("assistant", "We are now at the settings app, there are various entries including a 'display settings' entry. Let's click on the display settings", Tag.THOUGHTS),
            Message("assistant", """{"function":"click", "pos": [100, 200]}""", Tag.ACTION),
            Message("assistant", """{"continue":true}""", Tag.ACTION),
            Message("assistant", "", Tag.MODEL_END),
            Message("system", "[...] (view tree)", Tag.TREE),
            Message("assistant", "", Tag.MODEL_START),
            Message("assistant", "We are now in the display settings, which is scrollable. I can't see the dark mode there, let's scroll down a bit", Tag.THOUGHTS),
            Message("assistant", """{"function":"swipe", "direction": "down"}""", Tag.ACTION),
            Message("assistant", """{"continue":true}""", Tag.ACTION),
            Message("assistant", "", Tag.MODEL_END),
            Message("system", "[...] (view tree)", Tag.TREE),
            Message("assistant", "", Tag.MODEL_START),
            Message("assistant", "I can now see the dark mode setting. Let's click on the dark mode toggle and don't continue", Tag.THOUGHTS),
            Message("assistant", """{"function":"click", "pos": [350, 1420]}""", Tag.ACTION),
            Message("assistant", """{"function":"say", "text": "Dark mode enabled"}""", Tag.ACTION),
            Message("assistant", """{"continue":false}""", Tag.ACTION),
            Message("assistant", "", Tag.MODEL_END),
        ),
        listOf(
            // Show how to do searches in most apps
            Message("user", """Go to "Alice" in this app""", Tag.USER),
            Message("system", "[...]{\"input_fields\":[...\"Search\": {\"pos\": [683,182]},...]}", Tag.TREE),
            Message("assistant", "", Tag.MODEL_START),
            Message("assistant", "I am in an instant-messaging app, with a list of discussions, and persons. There is an input field search field, let's type \"Alice\" in it", Tag.THOUGHTS),
            Message("assistant", """{"function":"input_text", "pos": [683,182], "text": "Alice"}""", Tag.ACTION),
            Message("assistant", """{"continue":true}""", Tag.ACTION),
            Message("assistant", "", Tag.MODEL_END),
            Message("system", "[...]{\"buttons\":[...\"Alice VanDerSpiegel\": {\"pos\": [112,859]},...]}", Tag.TREE),
            Message("assistant", "", Tag.MODEL_START),
            Message("assistant", "I can now see the search results. I found \"Alice VanDerSpiegel\", let's click on it, and that's my final command to match the user's request so don't continue", Tag.THOUGHTS),
            Message("assistant", """{"function":"click", "pos": [112,859]}""", Tag.ACTION),
            Message("assistant", """{"continue":false}""", Tag.ACTION),
            Message("assistant", "", Tag.MODEL_END),
        ),
        listOf(
            // There can also be much simpler user requests like just pressing a button
            Message("user", "Press the download button", Tag.USER),
            Message("system", "[...]{\"buttons\":[...\"Download\": {\"pos\": [112,859]},...]}", Tag.TREE),
            Message("assistant", "", Tag.MODEL_START),
            Message("assistant", "This is an application store, I am on the NewPipe page of this store. I found the download button, let's click on it, and don't continue.", Tag.THOUGHTS),
            Message("assistant", """{"function":"click", "pos": [112,859]}""", Tag.ACTION),
            Message("assistant", """{"continue":false}""", Tag.ACTION),
            Message("assistant", "", Tag.MODEL_END),
        )
    )
    enum class Tag {
       // Model-generated tags
        MODEL_START,
        ACTION, // Action taken by the model
        THOUGHTS, // Thoughts of the model
        MODEL_END,

        TREE, // Description of the view tree (to be pruned)
        APP_PROMPT,

        // Those won't be pruned
        USER,
        OTHER,

        // Model-generated description of the view, but not in the same conversation
        DESCRIPTION,
    };
    data class Message(val sender: String, val text: String, val tag: Tag)
    //val messages = mutableListOf<Message>(Message("system", prompt, Tag.OTHER))
    val messages = if (convType == ConvType.AGENT) (listOf(Message("system", prompt, Tag.OTHER)) +
            examples[0] +
            examples[1] +
            Message("system", promptEnd, Tag.OTHER)).toMutableList()
    else if (convType == ConvType.DESCRIPTION) mutableListOf(Message("system", "You will receive a JSON tree view of the state of a smartphone. Describe in details what's on screen.", Tag.OTHER))
    else mutableListOf()

    fun complete(): List<Message> {
        val request =
            (messages
                .joinToString("\n") {
                    //<s><|user|>" + line + "<|end|>\n<|assistant|>
                    when(it.tag) {
                        Tag.USER -> "<|user|>${it.text}<|end|>"
                        Tag.OTHER, Tag.TREE, Tag.APP_PROMPT -> "<|system|>${it.text}"
                        Tag.THOUGHTS -> "Thoughts: ${it.text}"
                        Tag.DESCRIPTION -> "Screen description: ${it.text}"
                        Tag.ACTION -> it.text
                        Tag.MODEL_START -> "<|assistant|>"
                        Tag.MODEL_END -> "<|end|>"
                    }
                }) + "<|assistant|> "

        Log.d("PHH-TX", "Request is $request")
        //val response = together_xyz_complete(request)
        val response = llamacpp_complete(request)
        //val response = vllm_complete(request, if(convType == ConvType.AGENT) 128 else 512)
        Log.d("PHH-RX", "Response is $response")
        val response2 = response
            .replace(Regex("assistant:"), "")
            .split("\n")
            .map { it.trim() }
            // Take all the lines until one starts with System:
            .takeWhile {
                !it.startsWith("System:")  &&
                        "<|system|>" !in it &&
                        "<|user|>" !in it
                //Forbid all <|xx|> tags?
            }

        val response3 = mutableListOf<String>()
        for(m in response2) {
            if ("<|end|>" in m) break
            response3.add(m)
            if ("\"continue\"" in m) break
        }

        val resultCommands = mutableListOf<Message>()
        for(m in response3) {
            Log.d("PHH-Voice", "< $m")
            if (m.startsWith("Thoughts:")) {
                //Strip out Thoughts:
                val m2 = m.replace("Thoughts:", "").trim()
                messages.add(Message("assistant", m2, Tag.THOUGHTS))
            } else {
                val m2 = Message("assistant", m, Tag.ACTION)
                messages.add(m2)
                resultCommands.add(m2)
            }
        }

        File("/data/data/me.phh.phhassistant2/cache/conversation-${if(convType == ConvType.AGENT) "agent" else "description"}-${System.currentTimeMillis()}.txt").writeText(request + "\n" + response3.joinToString("\n"))
        return resultCommands
    }
}

class MyAccessibilityService : AccessibilityService() {
    val handler = Handler(HandlerThread("Assistant").also { it.start()}.looper)
    var conversation: Conversation? = null

    var latestTree: AccessibilityNodeInfo? = null
    var latestTreeTimestamp = 0L


    val fdroidPrompt = """
This is the F-Droid app. This is an app to install and update other apps.
You can search for apps, and update them.
If the user asked for a specific app by name, search it in the search bar.
To update apps, go in the updates tab, and click on the update button next to the app.
    """.trimIndent()

    val telegramPrompt = """
This is a Telegram app. This is an instant messaging app.
If the user asks you to do the setup, at some point the app will ask you for a login code.
When the app asks for a login code, and only then, you can ask the user for the login code.
When that happens, say the user "I need a login code to continue", and DO NOT continue so the user can answer.
When the app asks you "Is this the correct number?", press "Done".
The user will then give it to you, and then you'll be able continue the setup.

To send a message to someone, you need to do the following steps:
- click the search button
- type the name of the person,
- click on the name of the person in the search result. The name might contain additional garbage like "last seen recently", or some previous chat message, so ignore it. For instance, when searching for "Alice", you might see "Alice last seen recently", but you should click on "Alice last seen recently".
- click on the "Message" text input field to type the message.
- click send

    """.trimIndent()

    val whatsappPrompt = """
This is the Whatsapp app. This is an instant messaging app.
Setting-up whatsapp requires various stages. Wait for the Whatsapp to request the phone number before entering it.
When entering a phone number, the UI splits the country code and the phone number in two fields. You might have to split user's phone number based on whether they provided the country code or not.
You can directly type the country code in the edit text next to the phone number. No need to scroll through all the countries.
Setup usually starts with selecting a language, use device's default language by simply pressing the next button.

When the app asks for a login code, and only then, you can ask the user for the login code.
When that happens, say the user "I need a login code to continue", and DO NOT continue so the user can answer.
When the app asks you "Is this the correct number?", press "Done".
The user will then give it to you, and then you'll be able continue the setup.

If you need to re-send a new code, you might run into a wait time. You need to wait for the timer to expire before you can request a new code.

    """.trimIndent()

    val appPrompts = mapOf(
        "org.fdroid.fdroid" to fdroidPrompt,
        "org.telegram.messenger" to telegramPrompt,
        "com.whatsapp" to whatsappPrompt,
    )

    var appNameCache = mutableMapOf<String, String>()
    val constantNameMap = mapOf(
        "org.telegram.messenger.ApplicationLoaderImpl" to "Telegram",
    )

    fun browseViewNode(node: AccessibilityNodeInfo, buttons: JSONObject, input_fields: JSONObject, depth: Int = 0): JSONArray {
        val json = JSONArray()
        val pos = Rect()

        for(i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            //Log.d("PHH-Voice", "\t".repeat(depth) + "Child $i: ${if(child.isVisibleToUser) "VISIBLE" else "NOT VISIBLE"} ${child.className} ${child.extraRenderingInfo?.textSizeInPx} ${child.text}")
            //Log.d("PHH-Voice", "\t".repeat(depth) + " - ${child.contentDescription} ${child.stateDescription}")

            // Don't browse hidden options...
            if (!child.isVisibleToUser)
                continue

            // This means the node doesn't provide any useful information. Remove the useless fields to gain few tokens
            val isMeaninglessLayout = child.className == "android.view.View" &&
                    child.text == null &&
                    child.contentDescription == null

            // We'll skip this node altogether in that case
            // TODO: we'd need some img2txt to know what's in there
            val isImageOnlyButton = child.className == "android.widget.Button" &&
                    child.text == null &&
                    child.contentDescription == null &&
                    child.childCount == 0

            if (isImageOnlyButton) {
                Log.d("PHH-Voice", "\t".repeat(depth) + "Useless button with hint ${child.hintText}, error ${child.error}, state ${child.stateDescription}, tooltip ${child.tooltipText}, resourcename ${child.viewIdResourceName}, container title ${child.containerTitle}")
                continue
            }

            val ret = browseViewNode(child, buttons, input_fields, depth + 1)

            val obj = JSONObject()
            if (child.text != null) {
                obj.put("text", child.text)
                obj.put("textSize", child.extraRenderingInfo?.textSizeInPx)


            } else if(child.contentDescription != null) {
                obj.put("text", child.contentDescription)
            }
            if(child.isFocused)
                obj.put("focused", child.isFocused)
            if (!isMeaninglessLayout)
                obj.put("class", child.className
                    ?.replace(Regex("androidx?.(widget|view)."), ""))
            if (ret.length() > 0)
                obj.put("children", ret)

            if(!isMeaninglessLayout) {
                child.getBoundsInScreen(pos)
                obj.put(
                    "pos",
                    JSONArray().also {
                        it.put((pos.left + pos.right) / 2);
                        it.put((pos.top + pos.bottom) / 2)
                    })

                val posJson =
                    JSONObject().apply {
                        put("pos",
                            JSONArray().apply {
                                put((pos.left + pos.right) / 2);
                                put((pos.top + pos.bottom) / 2)
                            }
                        )
                    }
                if(obj.has("text")) {
                    if(child.isEditable) {
                        input_fields.put(obj.getString("text"), posJson)
                    }
                    else if(child.isClickable || child.isCheckable) {
                        buttons.put(obj.getString("text"), posJson)
                    }
                }
            }
            json.put(obj)
        }
        return json
    }


    private fun installedApps(): JSONArray {
        val packageManager = packageManager
        val intent = Intent(Intent.ACTION_MAIN, null)
        intent.addCategory(Intent.CATEGORY_LAUNCHER)
        val resInfos = packageManager.queryIntentActivities(intent, 0)
        val installedApps = JSONArray()
        for (resInfo in resInfos) {
            var appName = resInfo.activityInfo.applicationInfo.loadLabel(packageManager)
            if(constantNameMap.contains(appName.toString()))
                appName = constantNameMap[appName]!!
            installedApps.put(appName.toString())

            appNameCache[resInfo.activityInfo.applicationInfo.packageName] = resInfo.activityInfo.applicationInfo.loadLabel(packageManager).toString()
        }
        Log.d("PHH-Voice", "Installed apps are $installedApps")
        return installedApps
    }


    fun jsonView(node: AccessibilityNodeInfo): JSONObject {
        val packagename = node.packageName
        val json = JSONObject()
        json.put("current_app", appNameCache[packagename.toString()] ?: constantNameMap[packagename.toString()] ?: packagename.toString())
        val buttons = JSONObject()
        val input_fields = JSONObject()
        val view_tree = browseViewNode(node, buttons, input_fields)
        json.put("view_tree", view_tree)
        json.put("buttons", buttons)
        json.put("input_fields", input_fields)
        json.put("installed_apps", installedApps())
        return json
    }

    private fun tap(point: PointF) {
        val tap = StrokeDescription(
            android.graphics.Path().apply{ moveTo(point.x, point.y) },
            0,
            ViewConfiguration.getTapTimeout().toLong()
        )
        val builder = GestureDescription.Builder()
        builder.addStroke(tap)
        dispatchGesture(builder.build(), null, null)
    }

    fun describeScreen(): String {
        val jsonTree = synchronized(this) { jsonView(latestTree!!) }
        val describptionConversation = Conversation(Conversation.ConvType.DESCRIPTION)
        describptionConversation.messages.add(Conversation.Message("system",
            jsonTree.toString(), Conversation.Tag.TREE))
        describptionConversation.messages.add(Conversation.Message("system",
            "Describe the current screen to the user in details.", Conversation.Tag.OTHER))
        val result = describptionConversation.complete()
        val description = result.last().text
        Log.e("PHH-Voice", "Description of the current screen $description")
        return description
    }

    override fun onServiceConnected() {
        Log.d("PHH-Voice", "Accessibility service connected")

        thread {
            try {
                val serverSocket = ServerSocket(8801, 1, InetAddress.getByName("localhost"))

                while (true) {
                    val socket = serverSocket.accept()

                    val input = socket.getInputStream().bufferedReader()
                    val output = socket.getOutputStream().bufferedWriter()

                    val firstJsonTree = synchronized(this) { jsonView(latestTree!!) }

                    conversation = Conversation()
                    conversation!!.messages.add(Conversation.Message("system",
                        firstJsonTree.toString(), Conversation.Tag.TREE))
                    conversation!!.messages.add(Conversation.Message("system",
                        "Describe the current screen to the user in one sentence", Conversation.Tag.OTHER))
                    conversation!!.complete()

                    // Show the conversation to the user
                    // Skip first message, it's the prompt
                    //   and the second message, it's the view tree json
                    val conv = synchronized(this) { conversation }!!
                    for(i in 2 until conv.messages.size) {
                        val m = conv.messages[i]
                        output.write("${m.sender}: ${m.text}\n")
                    }
                    output.flush()

                    var skipUser = false
                    var lastViewTree = ""
                    while (true) {
                        val stillAlive = synchronized(this) { conversation != null }
                        if (!stillAlive)
                            break

                        var userStr = ""
                        if (!skipUser) {
                            output.write("> ")
                            output.flush()
                            userStr = (input.readLine() ?: break).trim()
                            if (userStr == "exit")
                                break
                        }

                        conv.messages
                            .filter { it.tag == Conversation.Tag.TREE || it.tag == Conversation.Tag.APP_PROMPT }
                            .forEach { conv.messages.remove(it) }

                        softKeyboardController.setShowMode(SHOW_MODE_HIDDEN)

                        val (newJson, buttons) = synchronized(this) {
                            val json = jsonView(latestTree!!)
                            json.toString() to json.getJSONObject("buttons")
                        }
                        if (newJson == lastViewTree) {
                            Log.e("PHH-Voice", "Tree didn't change...?")
                            output.flush()
                        }
                        lastViewTree = newJson

                        val currentPackage = synchronized(this) { latestTree!!.packageName.toString() }
                        if (appPrompts.containsKey(currentPackage)) {
                            conv.messages.add(Conversation.Message("system",  appPrompts[currentPackage]!!, Conversation.Tag.APP_PROMPT))
                        }
                        conv.messages.add(Conversation.Message("system",
                            newJson, Conversation.Tag.TREE))

                        val description = describeScreen()
                        conv.messages.add(Conversation.Message("system",
                            description, Conversation.Tag.DESCRIPTION))

                        output.write("refreshed tree\n")
                        output.flush()

                        if (!skipUser) {
                            // Add user request to the conversation
                            conv.messages.add(Conversation.Message("user", userStr, Conversation.Tag.USER))
                        }

                        val newCommands = conv.complete()
                        skipUser = false

                        // Remember our current view tree, so that when we do continue() we can wait for the tree to update
                        val currentTree = synchronized(this) { latestTreeTimestamp }

                        var skipUserSkipViewRefresh = false
                        for (cmd in newCommands) {
                            val line = cmd.text
                            output.write("---- $line\n")
                            output.flush()

                            try {
                                val json = JSONObject(line)
                                if (json.has("continue") && json.getBoolean("continue")) {
                                    output.write("Will continue without user intervention\n")
                                    Thread.sleep(10000)
                                    if (skipUserSkipViewRefresh) {
                                        continue
                                    }
                                    // Wait for the last tree update to be more than 1s old
                                    Log.e("PHH-Voice", "Waiting for tree update")
                                    while (currentTree == synchronized(this) { latestTreeTimestamp } ||
                                        (System.currentTimeMillis() - synchronized(this) { latestTreeTimestamp }) < 1000) {
                                        Thread.sleep(100)
                                    }
                                    Log.e("PHH-Voice", "Done waiting for tree update")
                                    skipUser = true
                                    continue
                                }

                                when (json.getString("function")) {
                                    "click" -> {
                                        val pos = json.getJSONArray("pos")
                                        // Find this pos in the list of buttons we gave to the model
                                        val buttonName = buttons.keys().asSequence()
                                            .map { it as String }
                                            .filter {
                                                buttons.getJSONObject(it).getJSONArray("pos").getDouble(0) == pos.getDouble(0) &&
                                                    buttons.getJSONObject(it).getJSONArray("pos").getDouble(1) == pos.getDouble(1)
                                            }.firstOrNull()

                                        val button = buttons.optJSONObject(buttonName)

                                        if (button == null) {
                                            Log.e("PHH-Voice", "No button found at this position")
                                            output.write("No button found at this position\n")
                                            output.flush()
                                            conv.messages.add(Conversation.Message("system", "No button found at this position", Conversation.Tag.OTHER))
                                            skipUser = true
                                            skipUserSkipViewRefresh = true
                                        } else {
                                            Log.e("PHH-Voice", "Clicking on button $buttonName")
                                            tap(
                                                PointF(
                                                    pos.getDouble(0).toFloat(),
                                                    pos.getDouble(1).toFloat()
                                                )
                                            )
                                        }
                                    }

                                    "global_action" -> {
                                        when (json.getString("action")) {
                                            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
                                            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
                                            "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
                                            "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                                            "dpad_up" -> performGlobalAction(GLOBAL_ACTION_BACK)
                                            "dpad_down" -> performGlobalAction(GLOBAL_ACTION_BACK)
                                            "dpad_left" -> performGlobalAction(GLOBAL_ACTION_BACK)
                                            "dpad_right" -> performGlobalAction(GLOBAL_ACTION_BACK)
                                            "dpad_center" -> performGlobalAction(GLOBAL_ACTION_BACK)
                                        }
                                    }

                                    "input_text" -> {
                                        val pos = json.getJSONArray("pos")
                                        tap(
                                            PointF(
                                                pos.getDouble(0).toFloat(),
                                                pos.getDouble(1).toFloat()
                                            )
                                        )
                                        Thread.sleep(1000) // Wait for the keyboard to show up

                                        val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                                        if (node != null) {
                                            val arguments = Bundle()
                                            arguments.putCharSequence(
                                                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                                json.getString("text")
                                            )
                                            node.performAction(
                                                AccessibilityNodeInfo.ACTION_SET_TEXT,
                                                arguments
                                            )
                                        } else {
                                            output.write("No input field found\n")
                                            output.flush()

                                            conv.messages.add(Conversation.Message("system", "System: No input field found\n", Conversation.Tag.OTHER))
                                            skipUser = true
                                            skipUserSkipViewRefresh = true
                                        }
                                    }

                                    "swipe" -> {
                                        val rect = Rect()
                                        latestTree!!.getBoundsInScreen(rect)
                                        val center = PointF(
                                            (rect.left + rect.right) / 2f,
                                            (rect.top + rect.bottom) / 2f
                                        )
                                        val direction = json.getString("direction")
                                        val start = PointF(center.x, center.y)
                                        val end = PointF(center.x, center.y)
                                        when (direction) {
                                            "up" -> start.y =
                                                (rect.bottom * 0.8 + rect.top * 0.1).toFloat()

                                            "down" -> start.y =
                                                (rect.top * 0.8 + rect.bottom * 0.1).toFloat()
                                        }
                                        val swipe = StrokeDescription(
                                            android.graphics.Path().apply {
                                                moveTo(start.x, start.y); lineTo(
                                                end.x,
                                                end.y
                                            )
                                            },
                                            0,
                                            500
                                        )
                                        val builder = GestureDescription.Builder()
                                        builder.addStroke(swipe)
                                        dispatchGesture(builder.build(), null, null)
                                    }
                                    "launch_app" -> {
                                        val pkg = (appNameCache + constantNameMap)
                                            .filter { it.value == json.getString("app") }
                                            .keys.firstOrNull()
                                        val intent = if(pkg != null) packageManager.getLaunchIntentForPackage(pkg) else null
                                        intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        if (intent != null) {
                                            startActivity(intent)
                                        } else {
                                            output.write("App not found\n")
                                            output.flush()
                                            conv.messages.add(Conversation.Message("system", "App not found", Conversation.Tag.OTHER))
                                            skipUser = true
                                            skipUserSkipViewRefresh = true
                                        }

                                    }
                                    "launch_intent" -> {
                                        val intent = Intent(json.getString("intent"))
                                        intent.data = android.net.Uri.parse(json.getString("data"))
                                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        startActivity(intent)
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("PHH-Voice", "Failed to parse JSON", e)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("PHH-Voice", "Failed to start server", e)
            }
        }
    }

    fun getRootNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var p = node
        while(p.parent != null)
            p = p.parent
        return p
    }

    val describeScreenRunnable = Runnable {
        describeScreen()
    }
    val screenshotRunnable = Runnable {
        takeScreenshot(0, Executor { command -> handler.post(command) }, object: AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(p0: ScreenshotResult) {
                // Write screenshot to /data/data/me.phh.phhassistant2/cache/
                val file = File("/data/data/me.phh.phhassistant2/cache/screenshot-${System.currentTimeMillis()}.png")
                val bmp = Bitmap.wrapHardwareBuffer(p0.hardwareBuffer, p0.colorSpace) ?: return
                FileOutputStream(file).use {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }

            override fun onFailure(p0: Int) {
            }
        })
    }
    override fun onAccessibilityEvent(p0: AccessibilityEvent) {
        Log.d("PHH-Voice", "Accessibility event $p0")
        val p = p0.source
        if (p != null) {
            synchronized(this) {
                latestTree = getRootNode(p)
                latestTreeTimestamp = System.currentTimeMillis()
            }
            handler.removeCallbacks(describeScreenRunnable)
            handler.removeCallbacks(screenshotRunnable)
            handler.postDelayed(describeScreenRunnable, 2000L)
            handler.postDelayed(screenshotRunnable, 1500L)
            // Just for logs
            //val json = browseViewNode(latestTree!!)
            //Log.e("PHH-A11Y", "Tree is $json")
            Log.e("PHH-A11Y", "Updated tree")
        }
    }

    override fun onInterrupt() {
        Log.d("PHH-Voice", "Accessibility service interrupted")
    }


    override fun onDestroy() {
        super.onDestroy()
        synchronized(this) {
            conversation = null
        }
    }
}

class MainInteractionService: VoiceInteractionService() {
    override fun onBind(intent: Intent?): IBinder? {
        Log.d("PHH-Voice", "Bound to voice interaction service")
        return super.onBind(intent)
    }

    override fun onReady() {
        super.onReady()
        Log.d("PHH-Voice", "Voice interaction service is ready")
    }

    override fun onLaunchVoiceAssistFromKeyguard() {
        super.onLaunchVoiceAssistFromKeyguard()
        Log.d("PHH-Voice", "Launching voice assist from keyguard")
    }

    override fun onPrepareToShowSession(args: Bundle, showFlags: Int) {
        super.onPrepareToShowSession(args, showFlags)
        Log.d("PHH-Voice", "Preparing to show session")
    }

    override fun showSession(args: Bundle?, flags: Int) {
        super.showSession(args, flags)
        Log.d("PHH-Voice", "Showing session")
    }
}

class MainInteractionSessionService: android.service.voice.VoiceInteractionSessionService() {
    fun browseViewNode(node: AssistStructure.ViewNode, depth: Int, left: Int, top: Int): JSONArray {
        val json = JSONArray()
        val myTop = top + node.top
        val myLeft = left + node.left

        for(i in 0 until node.childCount) {
            val child = node.getChildAt(i)
            Log.d("PHH-Voice", "\t".repeat(depth) + "Child $i: ${child.isActivated} ${if(child.visibility == 0) "VISIBLE" else "NOT VISIBLE"} ${child.alpha} ${child.className} ${child.textSize} ${child.text}")

            // Don't browse hidden options...
            if (child.visibility != 0)
                continue

            val htmlInfo = child.htmlInfo
            if(htmlInfo != null) {
                Log.d("PHH-Voice", "\t".repeat(depth) + "HTML info: ${htmlInfo}")
            }
            val ret = browseViewNode(child, depth + 1, myLeft, myTop)

            val obj = JSONObject()
            if (child.text != null) {
                obj.put("text", child.text)
                obj.put("textSize", child.textSize)
            }
            obj.put("class", child.className
                ?.replace("android.widget.", "")
                ?.replace("androidx.widget.", ""))
            if (ret.length() > 0)
                obj.put("children", ret)
            obj.put("pos", JSONArray().also { it.put(myLeft + child.left + child.width/2); it.put(myTop + child.top + child.height/2) })
            json.put(obj)
        }
        return json
    }


    override fun onCreate() {
        super.onCreate()
    }

    override fun onNewSession(args: Bundle): VoiceInteractionSession {
        return object:VoiceInteractionSession(this) {
            override fun onHandleAssist(state: AssistState) {
                val data = state.assistData
                val structure = state.assistStructure
                val content = state.assistContent

                Log.d("PHH-Voice", "Received handle assist $data $structure $content activityId ${state.activityId}")
                Log.d("PHH-Voice", "Intent: ${content?.intent}")
                if(content != null && content.intent.extras != null) {
                    val e = content.intent.extras!!
                    for(k in e.keySet()) {
                        Log.d("PHH-Voice", "\t$k - ${content.extras[k]}")
                    }
                }

                Log.d("PHH-Voice", "Structured data ${content?.structuredData}")
                Log.d("PHH-Voice", "Clip data: ${content?.clipData?.description}")
                Log.d("PHH-Voice", "Web URI: ${content?.webUri}")
                if(content?.extras != null) {
                    val e = content.extras!!
                    for(k in e.keySet()) {
                        Log.d("PHH-Voice", "\t$k - ${content.extras[k]}")
                    }
                }

                Log.d("PHH-Voice","data bundle")
                for(k in data!!.keySet()) {
                    Log.d("PHH-Voice", "\t$k - ${data[k]}")
                }

                for(i in 0 until structure!!.windowNodeCount) {
                    val node = structure.getWindowNodeAt(i)
                    Log.d("PHH-Voice", "Window node $i: $node ${node.title}")

                    val rootView = node.rootViewNode
                    val json = JSONArray()
                    for (j in 0 until rootView.childCount) {
                        val child = rootView.getChildAt(j)
                        val ret = browseViewNode(child, 1, node.left, node.top)
                        if (ret.length() > 0)
                            json.put(ret)
                    }
                    Log.d("PHH-Voice", "View json is $json")

                }
            }

            override fun onHandleScreenshot(screenshot: Bitmap?) {
                Log.d("PHH-Voice", "Received screenshot $screenshot")
            }
        }
    }
}
