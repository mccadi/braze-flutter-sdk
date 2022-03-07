package com.braze.brazeplugin

// Android V2 embedding

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.annotation.NonNull
import com.appboy.enums.Gender
import com.appboy.enums.Month
import com.appboy.enums.NotificationSubscriptionType
import com.appboy.events.SimpleValueCallback
import com.appboy.models.cards.Card
import com.appboy.models.outgoing.AttributionData
import com.braze.Braze
import com.braze.BrazeUser
import com.braze.models.inappmessage.IInAppMessage
import com.braze.models.inappmessage.IInAppMessageImmersive
import com.braze.models.outgoing.BrazeProperties
import com.braze.support.BrazeLogger
import com.braze.ui.activities.ContentCardsActivity
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import org.json.JSONObject
import java.math.BigDecimal
import java.util.*

class BrazePlugin : MethodCallHandler, FlutterPlugin, ActivityAware {
    private lateinit var context: Context
    private lateinit var channel: MethodChannel
    private lateinit var flutterCachedConfiguration: FlutterCachedConfiguration
    private var activity: Activity? = null

    //--
    // Setup
    //--

    private fun initPlugin(context: Context, messenger: BinaryMessenger) {
        flutterCachedConfiguration = FlutterCachedConfiguration(context, false)
        val channel = MethodChannel(messenger, "braze_plugin")
        channel.setMethodCallHandler(this)
        this.context = context
        this.channel = channel
        activePlugins.add(this)
    }

    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        this.initPlugin(flutterPluginBinding.applicationContext, flutterPluginBinding.binaryMessenger)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        activePlugins.remove(this)
        channel.setMethodCallHandler(null)
    }

    companion object {
        val TAG: String = BrazeLogger.getBrazeLogTag(BrazePlugin::class.java)

        // Contains all plugins that have been initialized and are attached to a Flutter engine.
        var activePlugins = mutableListOf<BrazePlugin>()

        //--
        // Braze public APIs
        //--

        /**
         * Used to pass in In App Message data from the Braze
         * SDK native layer to the Flutter layer
         */
        @JvmStatic
        fun processInAppMessage(inAppMessage: IInAppMessage) {
            if (activePlugins.isEmpty()) {
                BrazeLogger.w(TAG, "There are no active Braze Plugins. Not calling 'handleBrazeInAppMessage'.")
                return
            }

            val inAppMessageMap: HashMap<String, String> =
                hashMapOf("inAppMessage" to inAppMessage.forJsonPut().toString())

            for (plugin in activePlugins) {
                if (plugin.activity != null) {
                    plugin.activity?.runOnUiThread {
                        plugin.channel.invokeMethod("handleBrazeInAppMessage", inAppMessageMap)
                    }
                }
            }
        }

        /**
         * Used to pass in Content Card data from the Braze
         * SDK native layer to the Flutter layer
         */
        @JvmStatic
        fun processContentCards(contentCardList: List<Card>) {
            if (activePlugins.isEmpty()) {
                BrazeLogger.w(TAG, "There are no active Braze Plugins. Not calling 'handleBrazeContentCards'.")
                return
            }

            val cardStringList = arrayListOf<String>()
            for (card in contentCardList) {
                cardStringList.add(card.forJsonPut().toString())
            }
            val contentCardMap: HashMap<String, ArrayList<String>> = hashMapOf("contentCards" to cardStringList)

            for (plugin in activePlugins) {
                if (plugin.activity != null) {
                    plugin.activity?.runOnUiThread {
                        plugin.channel.invokeMethod("handleBrazeContentCards", contentCardMap)
                    }
                }
            }
        }
    }

    //--
    // ActivityAware
    //--

    override fun onDetachedFromActivity() {
        this.activity = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        onAttachedToActivity(binding)
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        this.activity = binding.activity
        if (IntegrationInitializer.isUninitialized && flutterCachedConfiguration.isAutomaticInitializationEnabled()) {
            BrazeLogger.i(TAG, "Running Flutter BrazePlugin automatic initialization")
            this.activity?.application?.let { IntegrationInitializer.initializePlugin(it, flutterCachedConfiguration) }
        }
    }

    override fun onDetachedFromActivityForConfigChanges() {
        onDetachedFromActivity()
    }

    //--
    // Braze SDK bindings
    //--

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        try {
            when (call.method) {
                "changeUser" -> {
                    val userId = call.argument<String>("userId")
                    Braze.getInstance(context).changeUser(userId)
                }
                "requestContentCardsRefresh" -> {
                    Braze.getInstance(context).requestContentCardsRefresh(false)
                }
                "launchContentCards" -> {
                    if (this.activity != null) {
                        val intent = Intent(this.activity, ContentCardsActivity::class.java)
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        this.context.startActivity(intent)
                    }
                }
                "logContentCardsDisplayed" -> {
                    Braze.getInstance(context).logContentCardsDisplayed()
                }
                "logContentCardClicked" -> {
                    val contentCardString = call.argument<String>("contentCardString")
                    if (contentCardString != null) {
                        Braze.getInstance(context).deserializeContentCard(contentCardString)?.logClick()
                    }
                }
                "logContentCardImpression" -> {
                    val contentCardString = call.argument<String>("contentCardString")
                    if (contentCardString != null) {
                        Braze.getInstance(context).deserializeContentCard(contentCardString)?.logImpression()
                    }
                }
                "logContentCardDismissed" -> {
                    val contentCardString = call.argument<String>("contentCardString")
                    if (contentCardString != null) {
                        Braze.getInstance(context).deserializeContentCard(contentCardString)?.setIsDismissed(true)
                    }
                }
                "logInAppMessageClicked" -> {
                    Braze.getInstance(context)
                        .deserializeInAppMessageString(call.argument("inAppMessageString"))?.logClick()
                }
                "logInAppMessageImpression" -> {
                    Braze.getInstance(context)
                        .deserializeInAppMessageString(call.argument("inAppMessageString"))?.logImpression()
                }
                "logInAppMessageButtonClicked" -> {
                    val inAppMessage = Braze.getInstance(context)
                        .deserializeInAppMessageString(call.argument("inAppMessageString"))
                    if (inAppMessage is IInAppMessageImmersive) {
                        val buttonId = call.argument<Int>("buttonId") ?: 0
                        for (button in inAppMessage.messageButtons) {
                            if (button.id == buttonId) {
                                inAppMessage.logButtonClick(button)
                                break
                            }
                        }
                    }
                }
                "addAlias" -> {
                    val aliasName = call.argument<String>("aliasName")
                    val aliasLabel = call.argument<String>("aliasLabel")
                    Braze.getInstance(context).runOnUser { user -> user.addAlias(aliasName, aliasLabel) }
                }
                "logCustomEvent" -> {
                    val eventName = call.argument<String>("eventName")
                    val properties = convertToBrazeProperties(
                        call.argument<Map<String, *>>("properties")
                    )
                    Braze.getInstance(context).logCustomEvent(eventName, properties)
                }
                "logPurchase" -> {
                    val productId = call.argument<String>("productId")
                    val currencyCode = call.argument<String>("currencyCode")
                    val price = call.argument<Double>("price") ?: 0.0
                    val quantity = call.argument<Int>("quantity") ?: 1
                    val properties = convertToBrazeProperties(
                        call.argument<Map<String, *>>("properties")
                    )
                    Braze.getInstance(context).logPurchase(
                        productId, currencyCode, BigDecimal(price),
                        quantity, properties
                    )
                }
                "addToCustomAttributeArray" -> {
                    val key = call.argument<String>("key")
                    val value = call.argument<String>("value")
                    Braze.getInstance(context).runOnUser { user -> user.addToCustomAttributeArray(key, value) }
                }
                "removeFromCustomAttributeArray" -> {
                    val key = call.argument<String>("key")
                    val value = call.argument<String>("value")
                    Braze.getInstance(context).runOnUser { user -> user.removeFromCustomAttributeArray(key, value) }
                }
                "setStringCustomUserAttribute" -> {
                    val key = call.argument<String>("key")
                    val value = call.argument<String>("value")
                    Braze.getInstance(context).runOnUser { user -> user.setCustomUserAttribute(key, value) }
                }
                "setDoubleCustomUserAttribute" -> {
                    val key = call.argument<String>("key")
                    val value = call.argument<Double>("value") ?: 0.0
                    Braze.getInstance(context).runOnUser { user -> user.setCustomUserAttribute(key, value) }
                }
                "setDateCustomUserAttribute" -> {
                    val key = call.argument<String>("key")
                    val value = (call.argument<Int>("value") ?: 0).toLong()
                    Braze.getInstance(context).runOnUser { user -> user.setCustomUserAttributeToSecondsFromEpoch(key, value) }
                }
                "setIntCustomUserAttribute" -> {
                    val key = call.argument<String>("key")
                    val value = call.argument<Int>("value") ?: 0
                    Braze.getInstance(context).runOnUser { user -> user.setCustomUserAttribute(key, value) }
                }
                "incrementCustomUserAttribute" -> {
                    val key = call.argument<String>("key")
                    val value = call.argument<Int>("value") ?: 0
                    Braze.getInstance(context).runOnUser { user -> user.incrementCustomUserAttribute(key, value) }
                }
                "setBoolCustomUserAttribute" -> {
                    val key = call.argument<String>("key")
                    val value = call.argument<Boolean>("value") ?: false
                    Braze.getInstance(context).runOnUser { user -> user.setCustomUserAttribute(key, value) }
                }
                "unsetCustomUserAttribute" -> {
                    val key = call.argument<String>("key")
                    Braze.getInstance(context).runOnUser { user -> user.unsetCustomUserAttribute(key) }
                }
                "setPushNotificationSubscriptionType" -> {
                    val type = getSubscriptionType(call.argument<String>("type") ?: "")
                    Braze.getInstance(context).runOnUser { user -> user.setPushNotificationSubscriptionType(type) }
                }
                "setEmailNotificationSubscriptionType" -> {
                    val type = getSubscriptionType(call.argument<String>("type") ?: "")
                    Braze.getInstance(context).runOnUser { user -> user.setEmailNotificationSubscriptionType(type) }
                }
                "addToSubscriptionGroup" -> {
                    val groupId = call.argument<String>("groupId")
                    Braze.getInstance(context).runOnUser { user -> user.addToSubscriptionGroup(groupId) }
                }
                "removeFromSubscriptionGroup" -> {
                    val groupId = call.argument<String>("groupId")
                    Braze.getInstance(context).runOnUser { user -> user.removeFromSubscriptionGroup(groupId) }
                }
                "setLocationCustomAttribute" -> {
                    val key = call.argument<String>("key")
                    val lat = call.argument<Double>("lat") ?: 0.0
                    val long = call.argument<Double>("long") ?: 0.0
                    Braze.getInstance(context).runOnUser { user -> user.setLocationCustomAttribute(key, lat, long) }
                }
                "requestImmediateDataFlush" -> {
                    Braze.getInstance(context).requestImmediateDataFlush()
                }
                "setFirstName" -> {
                    val firstName = call.argument<String>("firstName")
                    Braze.getInstance(context).runOnUser { user -> user.setFirstName(firstName) }
                }
                "setLastName" -> {
                    val lastName = call.argument<String>("lastName")
                    Braze.getInstance(context).runOnUser { user -> user.setLastName(lastName) }
                }
                "setDateOfBirth" -> {
                    val year = call.argument<Int>("year") ?: 0
                    val month = getMonth(call.argument<Int>("month") ?: 0)
                    val day = call.argument<Int>("day") ?: 0
                    Braze.getInstance(context).runOnUser { user -> user.setDateOfBirth(year, month, day) }
                }
                "setEmail" -> {
                    val email = call.argument<String>("email")
                    Braze.getInstance(context).runOnUser { user -> user.setEmail(email) }
                }
                "setGender" -> {
                    val gender = call.argument<String>("gender")
                    val genderUpper = gender?.uppercase(Locale.getDefault()) ?: ""
                    val genderEnum = when {
                        genderUpper.startsWith("F") -> {
                            Gender.FEMALE
                        }
                        genderUpper.startsWith("M") -> {
                            Gender.MALE
                        }
                        genderUpper.startsWith("N") -> {
                            Gender.NOT_APPLICABLE
                        }
                        genderUpper.startsWith("O") -> {
                            Gender.OTHER
                        }
                        genderUpper.startsWith("P") -> {
                            Gender.PREFER_NOT_TO_SAY
                        }
                        genderUpper.startsWith("U") -> {
                            Gender.UNKNOWN
                        }
                        else -> {
                            return
                        }
                    }
                    Braze.getInstance(context).runOnUser { user -> user.setGender(genderEnum) }
                }
                "setLanguage" -> {
                    val language = call.argument<String>("language")
                    Braze.getInstance(context).runOnUser { user -> user.setLanguage(language) }
                }
                "setCountry" -> {
                    val country = call.argument<String>("country")
                    Braze.getInstance(context).runOnUser { user -> user.setCountry(country) }
                }
                "setHomeCity" -> {
                    val homeCity = call.argument<String>("homeCity")
                    Braze.getInstance(context).runOnUser { user -> user.setHomeCity(homeCity) }
                }
                "setPhoneNumber" -> {
                    val phoneNumber = call.argument<String>("phoneNumber")
                    Braze.getInstance(context).runOnUser { user -> user.setPhoneNumber(phoneNumber) }
                }
                "setAttributionData" -> {
                    val network = call.argument<String>("network")
                    val campaign = call.argument<String>("campaign")
                    val adGroup = call.argument<String>("adGroup")
                    val creative = call.argument<String>("creative")
                    val attributionData = AttributionData(network, campaign, adGroup, creative)
                    Braze.getInstance(context).runOnUser { user -> user.setAttributionData(attributionData) }
                }
                "setAvatarImageUrl" -> {
                    val avatarImageUrl = call.argument<String>("avatarImageUrl")
                    Braze.getInstance(context).runOnUser { user -> user.setAvatarImageUrl(avatarImageUrl) }
                }
                "registerAndroidPushToken" -> {
                    val pushToken = call.argument<String>("pushToken")
                    Braze.getInstance(context).registerPushToken(pushToken)
                }
                "getInstallTrackingId" -> {
                    result.success(Braze.getInstance(context).deviceId)
                }
                "setGoogleAdvertisingId" -> {
                    val id = call.argument<String>("id") ?: return
                    val adTrackingEnabled = call.argument<Boolean>("adTrackingEnabled") ?: return
                    Braze.getInstance(context).setGoogleAdvertisingId(id, adTrackingEnabled)
                }
                "wipeData" -> {
                    Braze.wipeData(context)
                }
                "requestLocationInitialization" -> {
                    Braze.getInstance(context).requestLocationInitialization()
                }
                "enableSDK" -> {
                    Braze.enableSdk(context)
                }
                "disableSDK" -> {
                    Braze.disableSdk(context)
                }
                else -> result.notImplemented()
            }
        } catch (e: Exception) {
            result.error("Exception encountered", call.method, e)
        }
    }

    //--
    // Private methods
    //--

    /**
     * Attempts to fetch the current user and then runs a block on it
     */
    private fun Braze.runOnUser(block: (user: BrazeUser) -> Unit) {
        this.getCurrentUser(object : SimpleValueCallback<BrazeUser>() {
            override fun onSuccess(user: BrazeUser) {
                super.onSuccess(user)
                block(user)
            }
        })
    }

    private fun getSubscriptionType(type: String): NotificationSubscriptionType? {
        return when (type.trim()) {
            "SubscriptionType.subscribed" -> NotificationSubscriptionType.SUBSCRIBED
            "SubscriptionType.opted_in" -> NotificationSubscriptionType.OPTED_IN
            "SubscriptionType.unsubscribed" -> NotificationSubscriptionType.UNSUBSCRIBED
            else -> null
        }
    }

    private fun getMonth(month: Int): Month? {
        return Month.getMonth(month - 1)
    }

    private fun convertToBrazeProperties(arguments: Map<String, *>?): BrazeProperties {
        if (arguments == null) {
            return BrazeProperties()
        }

        val jsonObject = JSONObject(arguments)
        return BrazeProperties(jsonObject)
    }
}
