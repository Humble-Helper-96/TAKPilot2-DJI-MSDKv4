package com.dji.sdk.sample.takpilot2

import androidx.core.content.ContextCompat
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.dji.sdk.sample.R
import com.taklite.util.AppLog

/**
 * Pilot field guide — a read-in-the-field explanation of what every control does.
 *
 * **Audience is the pilot, not a developer.** No class names, no file paths, no SDK talk. Where
 * a limitation matters to a flight decision it IS stated plainly (what the FAA layer can't tell
 * you, when the altitude readout is approximate, what a local marker delete does and doesn't
 * do), because a guide that only lists happy paths is the kind that gets someone in trouble.
 *
 * **Every pilot-facing string in this file is written in ASD-STE100 Simplified Technical
 * English. Keep it that way when you edit.** STE is the aerospace controlled-language standard
 * (approved dictionary + 53 writing rules); it exists so that a reader who is tired, rushed, or
 * reading in a second language cannot mis-parse a safety-relevant instruction. The rules this
 * text is held to:
 *  - **One word, one meaning, one part of speech.** A short screen press is always "touch",
 *    never tap/press/hit. A long press is always "touch and hold". The airframe is always the
 *    "aircraft", never the drone. A marker is always a "marker", never a pin.
 *  - **Approved vocabulary.** "make sure" not ensure, "about" not approximately, "use" not
 *    utilize, "let" not allow, "get" not obtain, "but" not however, "because of" not due to.
 *  - **Active voice, simple tenses, no -ing forms.** "The app sends the marker", not "the
 *    marker is sent" or "sending the marker".
 *  - **Sentence length**: 20 words max for an instruction, 25 for description. Six sentences
 *    max per paragraph, one topic each.
 *  - **Conditions come first**: "If the signal is weak, select Low" — never the reverse.
 *  - **No idiom, metaphor, or humour.** They are the first thing to fail a tired reader and the
 *    first thing to fail a translator.
 *  - **Warnings open with the command**, then the reason.
 *
 * The one deliberate exception: **on-screen control labels are quoted verbatim** even when they
 * are not STE ("Drop Marker at Crosshair", "Enroll & Connect"). STE treats these as technical
 * names, and a guide that renames the button a pilot is hunting for is worse than useless.
 *
 * **The icon examples are live views, not pictures.** Each one is the real toolbar widget —
 * [BatteryGaugeView], [SignalBarsView], [LiveToggleView], [RecordToggleView], the TAK badge with
 * its status dot — constructed here and driven into the state being described. Screenshots or
 * hand-drawn copies would silently go stale the next time an icon changes; these can't, because
 * they ARE the icons. It also means a state shown here is genuinely reachable in the app.
 */
class FieldGuideActivity : AppCompatActivity() {

    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_field_guide)
        AppLog.v(TAG, "field guide opened")
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_menu)
        }
        content = findViewById(R.id.fieldGuideContent)

        title("TAKPilot2 Field Guide")
        lede("This guide shows what the app does. It also shows what each control on the " +
            "flight screen does. Read it before you fly.")

        sectionOne()
        sectionTwo()
        sectionThree()
        sectionFour()
        sectionFive()

        divider()
        body("If this guide does not agree with the aircraft, obey the aircraft. Then tell " +
            "the person who maintains the app.")
        spacer(24)
    }

    // ---------------------------------------------------------------- Section 1

    private fun sectionOne() {
        section("1. What this app is for")
        body("TAKPilot2 flies your DJI aircraft. At the same time, it sends what the aircraft " +
            "sees to the shared TAK map of your team. The app does these three things together:")

        bullet("Your aircraft shows on the TAK map of all your team. Its position, heading " +
            "and altitude change as it flies.")
        bullet("The app also sends the point on the ground where the camera looks. Your team " +
            "sees where the aircraft is and what it looks at.")
        bullet("You can put markers on what you see. The markers show on the screens of your " +
            "team in a few seconds.")

        spacer(10)
        body("The app can also send live video to a server, and your team can look at this " +
            "video. It shows the TAK markers of other operators on your map. It shows the FAA " +
            "altitude limit where you fly.")

        note("Do not use this app for firmware updates, compass calibration, gimbal " +
            "calibration or aircraft registration. Do these tasks first with the DJI app.")

        note("If the TAK icon on the flight screen is red, the app does not send data to your " +
            "team. The aircraft flies correctly, but your team cannot see the aircraft or " +
            "your markers.")
    }

    // ---------------------------------------------------------------- Section 2

    private fun sectionTwo() {
        section("2. Pre-Flight Setup")
        body("You set these items on the ground. The app keeps them for the next flight. " +
            "Usually you set them one time. Change them only for a new area, a new server or " +
            "a new task.")

        sub("1. Aircraft Settings")
        body("The app sends these safety limits to the aircraft at each connection. The " +
            "heights and the distance are in feet.")
        bullet("Max altitude - the maximum height the aircraft lets you fly.")
        bullet("Max distance - the maximum distance from the home point. At this limit the " +
            "aircraft stops and holds its position. It does not come back without your " +
            "command.")
        bullet("RTH altitude - the height the aircraft climbs to before it flies home. Set " +
            "this height more than the highest obstacle between you and the aircraft.")
        bullet("Battery Warning - the level where the aircraft tells you the battery is low.")
        bullet("Battery Critical - the level where the aircraft lands on its own. The aircraft " +
            "lands where it is.")
        warn("Do not rely on these two fields. Some aircraft keep their own battery levels and " +
            "refuse a change. The Mini 2 is one of them: it warns at 20% and lands at 10%, and " +
            "this app cannot change these levels. If your aircraft refuses them, the app makes " +
            "the two fields read-only and tells you the levels the aircraft holds. Read that " +
            "line and plan your flight for those levels.")
        bullet("Stick mode - what the two sticks do. Mode 2 is usual. Change this only if you " +
            "know the aircraft uses a different mode.")
        bullet("If the signal is lost - the action of the aircraft if it loses the " +
            "controller: Return Home, Hover or Land. The aircraft does this action without " +
            "the app. It works if your phone stops during the flight. Usually, select Return " +
            "Home.")
        note("To keep the value that is already in the aircraft, leave the field empty.")
        warn("If the RTH altitude is more than the Max altitude, the app shows a warning " +
            "below the fields. The aircraft cannot climb to its return height. Correct one of " +
            "the two values.")

        body("Apply Updated Settings to Aircraft sends all of these to the aircraft now. Then " +
            "the app asks the aircraft what it holds and shows the answer below the button. " +
            "Read that line. It shows the aircraft, not what you typed.")
        note("The line above the answer tells you how many settings the aircraft accepted. If " +
            "the aircraft refused a setting, the app gives its name. A refused setting keeps " +
            "the value the aircraft already holds.")
        note("The stick mode goes to the aircraft only when you touch Apply to Aircraft. The " +
            "app never changes the sticks on its own.")
        note("Lock these settings makes the fields read-only. To unlock them, the app asks " +
            "for a password. The lock stops a change by accident. It is not security.")

        body("Obstacle avoidance has three boxes. The app sends these three settings to the " +
            "aircraft at each connection.")
        bullet("Obstacle avoidance enabled - the aircraft looks for obstacles and stops before " +
            "it hits them.")
        bullet("Avoid obstacles during Return to Home - the aircraft looks for obstacles also " +
            "when it flies home on its own.")
        bullet("Landing protection - the aircraft looks at the ground before it lands. If the " +
            "ground is not safe, it does not land.")
        body("Below the boxes, the app shows what the aircraft reports now. Read this line. " +
            "The boxes show what you selected, but this line shows the aircraft.")
        note("The app sends these settings only when the aircraft is on the ground. If the " +
            "aircraft is armed or in the air, the app does not change them.")
        note("The Mini 2 has no obstacle sensors. These settings do nothing on a Mini 2.")

        sub("2. Video Streaming")
        body("This section is optional. If your team has a video server, type its address, " +
            "its port, the video name for this aircraft, and the login. Then select the " +
            "quality: Low, Standard or High. Usually, select Standard.")
        bullet("Low - 480 lines. Use it if the connection is weak or you use mobile data.")
        bullet("Standard - 720 lines. Use it for a usual flight.")
        bullet("High - 1080 lines. Use it only on a strong connection.")
        body("The app sends the video as H.264. Video servers and web browsers can show this " +
            "video.")
        note("These settings do not start the video. Use the LIVE button in flight to start " +
            "and stop the video.")
        note("The full address shows below the fields. If it shows (NO PASSWORD) and your " +
            "server needs one, the video cannot connect.")

        sub("3. TAK Server Connection")
        body("These fields set the address of the TAK server of your team and your identity " +
            "on it. Type the address, the two ports, your username and your password. Type " +
            "the callsign for your aircraft. Then touch Enroll & Connect. Usually you do this " +
            "one time for each server.")
        body("The channel list is below these fields. These are the groups for your login. " +
            "The channels you select receive the position of the aircraft and your markers. " +
            "If you select no channel, the server selects the channels.")
        note("If the phone has no network, the app tells you. It does not show an error " +
            "about the server. Look at the Network line on the home screen first.")

        sub("4. Elevation Data (DTED)")
        body("This is the terrain data for your flight area. You import one file for each " +
            "region. The data tells the app the height of the ground below the aircraft.")
        body("The terrain data improves two functions:")
        bullet("Markers go to the correct position. Without the data, a marker on a slope " +
            "can be too near or too far.")
        bullet("The altitude shows the true height above the ground. Without the data, it " +
            "shows the height above your takeoff point.")

        sub("5. FAA Airspace Ceilings (UASFM)")
        body("This downloads the FAA UAS Facility Map altitudes for an area. The flight " +
            "screen then shows the ceiling at your position. Type a center point and a " +
            "radius, or touch Use My Location. Check the size, then download the data.")
        note("Download this data on a wifi connection before you go to the flight area. In " +
            "flight, the app reads the data from the phone and does not need a signal.")
        warn("Do not use this data as an approval to fly. It shows the altitude that the FAA " +
            "usually approves, but it is not an approval. The FAA changes these maps and the " +
            "data can become out of date. You must get your own airspace approval.")

        sub("6. Map Display")
        body("This sets the map type for the small map on the flight screen. Select Street, " +
            "Hybrid (satellite images), or a custom map of your team. Then touch Save Map " +
            "Display.")
        note("This setting changes the small map on the flight screen only. It does not " +
            "change the map of your team.")
    }

    // ---------------------------------------------------------------- Section 3

    private fun sectionThree() {
        section("3. The Flight Screen")
        body("The live camera image fills the screen. The toolbar is across the top: the " +
            "status icons are on the left and the buttons are on the right. The readout is " +
            "down the right side, and the small map is in the bottom right corner. This " +
            "section shows each control in sequence.")

        sub("Toolbar: left side (status)")

        entry(
            listOf(icon(R.drawable.ic_menu) to "Menu"),
            "Menu",
            "This button closes the flight screen and shows the home screen. The aircraft " +
                "continues to fly and stays connected to TAK.",
        )

        entry(
            listOf(
                takBadge(connected = true) to "Connected",
                takBadge(connected = false) to "Not connected",
            ),
            "TAK connection",
            "A green dot shows that your aircraft is on the TAK map of your team. A red dot " +
                "shows that it is not on the map. You can fly, but your team cannot see the " +
                "aircraft. Touch the icon to connect or disconnect.",
        )

        entry(
            listOf(
                battery(85) to "85%",
                battery(24) to "24%",
                battery(9) to "9%",
            ),
            "Battery",
            "This ring shows the charge in the battery of the aircraft. The ring becomes " +
                "empty as you fly. Green shows more than 30%. Yellow shows 16% to 30%, and " +
                "red shows 15% or less. Land the aircraft when the ring is yellow. Do not " +
                "wait for red.",
        )

        entry(
            listOf(
                signal(90) to "Strong",
                signal(60) to "Medium",
                signal(20) to "Weak",
            ),
            "Controller signal",
            "These bars show the strength of the signal between the controller and the " +
                "aircraft. The percentage is next to the bars. Three or two green bars show a " +
                "good signal. One yellow bar shows a weak signal. Red with no bars shows that " +
                "you can lose the signal. If the signal is weak, fly the aircraft nearer or " +
                "lift the controller.",
        )

        entry(
            listOf(
                gps(hasFix = true) to "Position",
                gps(hasFix = false) to "No position",
            ),
            "GPS satellites",
            "This shows the quantity of satellites that the aircraft receives. Green shows " +
                "that the aircraft has its position. Grey shows that it does not have its " +
                "position. Wait for green before you take off. Without a position, the " +
                "aircraft cannot hold its position, cannot set a home point, and cannot come " +
                "home correctly.",
        )

        entry(
            listOf(
                image(R.drawable.ic_rth_home_set) to "Home set",
                image(R.drawable.ic_rth) to "No home",
            ),
            "Return to Home",
            "Touch this button to send the aircraft home. The app asks you to confirm. Touch " +
                "the button again during the return to stop it and get control.\n\n" +
                "The house becomes green when the home point is set. This shows that the " +
                "aircraft has a position to return to.\n\n" +
                "Touch and hold the button to move the home point to your position. Use this " +
                "function if you moved away from the takeoff point. The app asks you to " +
                "confirm, because this changes where the aircraft flies.",
        )

        sub("Toolbar: right side (buttons)")

        entry(
            listOf(image(R.drawable.ic_drop_pin) to "Marker"),
            "Put a marker",
            "This button puts a marker on the ground at the center of the camera image. " +
                "Point the camera at the target, then touch the button. The app opens the " +
                "\"Drop Marker at Crosshair\" window. Select the type (Friendly, Hostile, " +
                "Neutral or Unknown) and type a name. The app then sends the marker to your " +
                "team.\n\n" +
                "Touch and hold the button to open the \"Dropped Markers\" list. In the list " +
                "you can move a marker to the camera position, change its name, change its " +
                "type, send it again, or delete it.",
            listOf(
                "If the aircraft does not have a GPS position and a gimbal position, the app " +
                    "does not put the marker.",
                "If you delete a marker, the app removes it from your screen only. It stays " +
                    "on the screens of your team for 3 days.",
            ),
        )

        entry(
            listOf(arPill(on = false) to "Off", arPill(on = true) to "On"),
            "AR: markers on the video",
            "This function draws the markers on the live image at their true positions. You " +
                "can then see which building, vehicle or hill a marker identifies. The button " +
                "becomes green when the function is on.\n\n" +
                "A marker outside the camera image shows as a small arrow at the edge of the " +
                "image. The arrow shows the direction to turn the camera.\n\n" +
                "Touch and hold the button to select what the app draws:\n" +
                "- My Markers\n" +
                "- Team Markers\n" +
                "- Team Positions\n" +
                "- Air Traffic\n" +
                "- Weather\n\n" +
                "You can also set the range for air traffic to 2.5, 5 or 15 miles. If you " +
                "set an item to off, the app removes it from the image immediately. The app " +
                "always shows ground markers to 5 miles.",
            listOf(
                "If you move the camera quickly, the markers move on the image. They become " +
                    "correct when you stop. This is normal, because the position data and the " +
                    "video do not arrive at the same time.",
                "This function shows which object a marker identifies. It does not give an " +
                    "accurate position. For an accurate position, put the crosshair on the " +
                    "object and put a marker.",
                "Air traffic positions can be about ten seconds old. A fast aircraft is in " +
                    "front of its marker.",
            ),
        )

        entry(
            listOf(image(R.drawable.ic_camera_shutter) to "Photo"),
            "Photo",
            "This button takes a photo. The app saves the photo to the card in the aircraft, " +
                "not to your phone. The camera then goes back to video.",
        )

        entry(
            listOf(zoomPill("1X") to "Normal", zoomPill("2X") to "2X view"),
            "Zoom",
            "This button changes the camera between the normal view and the 2X view. It " +
                "changes the camera image. Your team sees the same view in the video.",
        )

        entry(
            listOf(image(R.drawable.ic_resync) to "Re-sync"),
            "Video re-sync",
            "This button corrects the video image. Blocks or marks can occur in the image, " +
                "usually when the camera looks at the same scene for a long time. If this " +
                "occurs, touch the button. The image becomes correct in a few seconds. This " +
                "changes your image only. The aircraft continues to fly.",
        )

        entry(
            listOf(
                live(LiveToggleView.State.OFF) to "Off",
                live(LiveToggleView.State.LIVE) to "Video on",
                live(LiveToggleView.State.RECONNECTING) to "Connects again",
            ),
            "LIVE: video to your team",
            "This button starts and stops the live video to the video server of your team. " +
                "First, set the server data in Pre-Flight Setup.\n\n" +
                "A yellow button that flashes shows that the connection stopped. The app " +
                "tries to connect again without your command. Do not touch the button. If you " +
                "touch it, the app stops and does not try again.",
        )

        entry(
            listOf(
                rec(recording = false) to "Off",
                rec(recording = true) to "Records",
            ),
            "REC: record to the aircraft",
            "This button records video to the card in the aircraft. It is independent of the " +
                "live video. You can use one function, both functions, or no function. The " +
                "card keeps the full quality, but the live video to your team has a lower " +
                "quality.",
        )

        sub("On the video image")

        entry(
            emptyList(),
            "The crosshair",
            "The crosshair shows the center of the camera image. This is the position where " +
                "a marker goes.\n\n" +
                "The ring in the center changes color. The color shows the accuracy of a " +
                "marker at this moment. The accuracy changes with the angle of the camera. " +
                "You can read this angle on the GIMBAL line of the readout. The angles are " +
                "different if you loaded terrain data (DTED) for your area.\n\n" +
                "WITH terrain data:\n" +
                "GREEN: 25° down or more. The error is about 10 ft.\n" +
                "YELLOW: 10° to 25° down. The error is about 50 ft.\n\n" +
                "WITHOUT terrain data:\n" +
                "GREEN: 30° down or more. The error is about 50 ft.\n" +
                "YELLOW: 15° to 30° down. The error is about 100 ft.\n\n" +
                "RED: less than the yellow angle. Do not put a marker. Point the camera down " +
                "more, or fly nearer.\n\n" +
                "When the camera is near horizontal, a small error in the angle moves the " +
                "marker a long distance on the ground. A steep angle is more accurate than a " +
                "view from a long distance. If the position of a marker is important, fly " +
                "nearer and point the camera down. Do not use the zoom from a long distance. " +
                "Without terrain data, the app must calculate with flat ground, and this " +
                "adds more error.",
            listOf(
                "These values are correct only with a good GPS position. A weak GPS position " +
                    "or large metal structures near the aircraft cause more error at all " +
                    "angles.",
                "The app uses the terrain data at the current position of the aircraft. If " +
                    "you fly out of the area of your data, the ring changes to the angles for " +
                    "no terrain data.",
            ),
        )

        entry(
            emptyList(),
            "Quick marker: touch the crosshair",
            "Touch the crosshair to put a marker immediately. The app does not ask you " +
                "questions. The type is always Unknown and the name is always " +
                "${com.dji.sdk.sample.tak.TakDropMarkers.QUICK_NAME}. Your team can " +
                "identify it quickly.\n\n" +
                "There is only one quick marker. To move it, point the camera at the new " +
                "target and touch and hold the crosshair. The marker moves on the screens of " +
                "all your team. If you touch the crosshair again, the app does not put a " +
                "second marker.\n\n" +
                "Use the quick marker to show your team what you look at now. To keep a " +
                "record of a position, use the marker button. With that button you can set a " +
                "name and a type.",
            listOf(
                "To remove the quick marker, delete it from the marker list. Touch and hold " +
                    "the marker button to open the list. Then you can put a new quick marker.",
                "The quick marker has the same rules as other markers. If you delete it, the " +
                    "app removes it from your screen only. It stays on the screens of your " +
                    "team.",
            ),
        )

        entry(
            emptyList(),
            "Obstacle distances",
            "If the aircraft sees an obstacle, the app draws a mark at the edge of the video " +
                "nearest to it. The mark shows the distance in feet.\n\n" +
                "A curved line at the left or the right edge shows an obstacle at that side. " +
                "An arrow with the word FWD shows an obstacle in front. An arrow with the word " +
                "REAR shows an obstacle behind. The camera cannot show you what is behind, so " +
                "read the REAR arrow with care.\n\n" +
                "A mark shows when an obstacle is nearer than about 39 ft. The marks are " +
                "yellow first. They become red, thicker and brighter as the aircraft gets " +
                "nearer. Red is 13 ft or less.",
            listOf(
                "An edge with no mark does not mean the direction is clear. It can also mean " +
                    "the aircraft has no sensor for that direction. The Air 2S has sensors in " +
                    "front, behind, up and down, but none at the sides. The Mini 2 has no " +
                    "obstacle sensors, and this display always stays empty.",
                "The app does not show the distance up or down yet.",
                "These marks are an aid. They do not replace your eyes. Keep the aircraft in " +
                    "sight.",
            ),
        )

        entry(
            emptyList(),
            "Warnings (top left)",
            "A box below the toolbar shows a warning. RED means act now. AMBER means know " +
                "it.\n\n" +
                "Most warnings come from the aircraft, in the words of the aircraft. Examples " +
                "are \"Cannot takeoff in a no-fly zone\", a compass problem, or a request to " +
                "calibrate. If the motors do not start, read this box first. The app adds its " +
                "own warnings for the return to home, the battery levels you set, the " +
                "altitude and distance limits, a missing home point, and high wind.\n\n" +
                "The box shows one warning at a time, the most important one. If there are " +
                "more, the box shows a count, for example \"+2\". Each warning stays on the " +
                "screen for a few seconds, so you can read it. A more important warning " +
                "replaces a less important one immediately. The box goes away when the " +
                "condition goes away.",
            listOf(
                "The app hides no warning from you. If the aircraft reports it, you see it. " +
                    "Some warnings are only reminders, such as a request to examine the " +
                    "propellers.",
                "The warnings stop when you leave the flight screen. They start again when " +
                    "you come back.",
            ),
        )

        sub("The readout: right side")

        entry(
            emptyList(),
            "Exposure slider (top right)",
            "This slider makes the image brighter or darker. The camera adjusts the exposure " +
                "automatically. Use the slider when the automatic exposure is not correct. " +
                "Examples are a dark object against snow, or a bright sky above dark ground. " +
                "The numbers below the slider show the values of the camera.",
        )

        entry(
            emptyList(),
            "Clock",
            "Below the slider the app shows the time of the phone, as hours, minutes and " +
                "seconds. Use it to give a time to your team, and to make a note of an event " +
                "in the flight.",
        )

        entry(
            emptyList(),
            "Aircraft data",
            "Below the clock the app shows three lines:\n" +
                "- the callsign of your aircraft, then its speed\n" +
                "- its height above the ground, then its height above sea level\n" +
                "- its latitude and longitude\n\n" +
                "Below these lines the app shows GIMBAL and the angle of the camera. The " +
                "color of this angle is the color of the crosshair ring. It tells you if a " +
                "marker at this angle is accurate.",
            listOf(
                "The height shows AGL if terrain data covers your position. AGL is the true " +
                    "height above the ground below the aircraft.",
                "The height shows ALT if there is no terrain data. ALT is the height above " +
                    "your takeoff point. This value is different if the ground below the " +
                    "aircraft is higher or lower.",
                "MSL is the height above sea level. Aviation charts and airspace limits use " +
                    "this value. MSL needs terrain data for your takeoff point only. If there " +
                    "is no data, MSL shows a dash. MSL can show a value when the line above " +
                    "shows ALT, because the app calculates the two values separately.",
            ),
        )

        entry(
            emptyList(),
            "RTH and HOME lines",
            "RTH shows the return height that the aircraft holds now. This is the height the " +
                "aircraft climbs to before it flies home. It shows two dashes until the " +
                "aircraft reports the height. Two dashes mean that the app does not know the " +
                "height. They do not mean that the height is zero.\n\n" +
                "HOME shows the distance to the home point, then the direction to it in " +
                "degrees true. Use these two numbers to know how far the aircraft must fly to " +
                "come back.",
            listOf(
                "The RTH line shows the AIRCRAFT, not the value you typed in Pre-Flight " +
                    "Setup. If the two are different, the aircraft did not accept the value. " +
                    "Send it again with Apply to Aircraft.",
                "The HOME line shows dashes until the aircraft has a GPS position and a home " +
                    "point.",
            ),
        )

        entry(
            emptyList(),
            "FAA ceiling line",
            "This line shows only if you downloaded the FAA data. It shows the published " +
                "ceiling at the position of the aircraft. It becomes red if you fly above " +
                "the ceiling.\n\n" +
                "The line shows AGL, because FAA ceilings are always heights above the " +
                "ground. Compare this value with the AGL line of the readout. Do not compare " +
                "it with the MSL line.\n\n" +
                "Grey \"Class G\" shows that the FAA has no facility map at this position. " +
                "The usual limit of 400 ft is applicable. Yellow \"no data here\" shows that " +
                "you flew out of the area of your data. The app does not know the limit. " +
                "This is not an approval to fly.",
        )

        entry(
            emptyList(),
            "The map",
            "This is the small map in the bottom right corner. North is always at the top " +
                "and the aircraft is always in the center. The red line goes from the home " +
                "point to the aircraft, and shows your route back. The map also shows the TAK " +
                "markers of other operators. Touch a marker to remove it from your map only.\n\n" +
                "Touch the button at the bottom left of the map to change how much ground you " +
                "see. WIDE shows the full distance you are permitted to fly. NEAR shows less " +
                "ground with more detail. The app keeps your choice for the next flight.\n\n" +
                "Touch the map two times quickly to make it twice as large. Touch it two times " +
                "again to make it small. The larger map covers the readouts above it. The " +
                "flight screen always starts with the small map.",
            listOf(
                "Map data: OpenStreetMap contributors, for the street map. Satellite images: " +
                    "Esri World Imagery. A custom map shows the data of its own supplier.",
            ),
        )

        sub("The resource bar: bottom left")

        entry(
            emptyList(),
            "System resources",
            "Usually this bar is not on the screen. To show it, open Debug Log from the home " +
                "screen and select \"Show system resources on flight screen\". The bar covers " +
                "a part of the video, so use it only when you must examine the phone.\n\n" +
                "The bar has four groups:\n" +
                "- SYS: the free memory of the phone, then its total memory\n" +
                "- APP: the memory that the app uses\n" +
                "- CPU: the load on the phone, then the load from the app\n" +
                "- TAK: the quantity of TAK contacts that the app holds\n\n" +
                "Look at TAK. The number must go up and down with the quantity of persons and " +
                "aircraft near you. If it only goes up across a flight, tell the person who " +
                "maintains the app.",
            listOf(
                "The first number of CPU can show a dash. Android does not give this value to " +
                    "an app. This is normal and it is not a fault.",
                "The app writes these same values to the log every 30 seconds. The log keeps " +
                    "them if you do not show the bar.",
            ),
        )
    }

    // ---------------------------------------------------------------- Section 4

    private fun sectionFour() {
        section("4. Flight path records")
        body("The app records the path of each flight. The recording is automatic. There is " +
            "no switch, and there is nothing to start or stop.")

        sub("When the app records")
        bullet("The recording starts when the aircraft leaves the ground.")
        bullet("The recording stops when the aircraft is on the ground for 10 seconds. A " +
            "short touch on the ground does not divide the flight into two records.")
        bullet("A TAK server is not necessary. A network is not necessary. The app records " +
            "each flight also when the phone is fully offline.")
        bullet("No GPS, no points. When the aircraft flies without a GPS position, the app " +
            "records nothing for that time. It does not write a false position.")

        sub("Where the records are")
        body("Open Downloads/TAKPilotFlights on the phone. Each flight makes two files with " +
            "the same name:")
        bullet(".gpx - the track. Import it into ATAK or Google Earth to see the flight path " +
            "on a map.")
        bullet(".csv - a table with one row each second: time, position, altitude, speed, " +
            "heading, battery and satellite count. Open it in a spreadsheet.")

        note("The folder keeps approximately 50 MB - months of flights. When it is full, the " +
            "app deletes the oldest files. Copy a record to a different location if you must " +
            "keep it permanently.")
        note("If the app stops during a flight, the record is safe. The track file appears " +
            "the next time you start the app.")
    }

    // ---------------------------------------------------------------- Section 5

    private fun sectionFive() {
        section("5. What this build cannot do")
        body("All the controls on the flight screen operate. These functions are not in this " +
            "build:")

        bullet("A correction for the position of a marker. If the markers of the app are all " +
            "in the same wrong direction, you cannot correct this in the app. Report the " +
            "error. A new build is necessary.")
        bullet("A measurement of the camera angle of view. The app uses the published values " +
            "for the aircraft. A marker near the edge of the picture can be less accurate " +
            "than a marker in the center. You can change the values by hand with the AR " +
            "button on the flight screen.")
        bullet("A thermal camera. This build is for a camera with visible light only.")

        warn("This build was flight-tested on the DJI Mini 2 only. On a different aircraft, " +
            "examine each control on your first flight, and keep the aircraft in sight.")
    }

    /** Action-bar back arrow behaves the same as the system back gesture. */
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    // ------------------------------------------------------- content builders

    private fun title(text: String) = content.addView(TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE); textSize = 24f
        setTypeface(null, android.graphics.Typeface.BOLD)
    })

    private fun lede(text: String) = content.addView(TextView(this).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(applicationContext, R.color.tp_text_secondary)); textSize = 14f
        setPadding(0, dp(6), 0, dp(4))
    })

    private fun section(text: String) {
        divider()
        content.addView(TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE); textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(8))
        })
    }

    private fun sub(text: String) = content.addView(TextView(this).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(applicationContext, R.color.tp_accent)); textSize = 15f
        setTypeface(null, android.graphics.Typeface.BOLD)
        letterSpacing = 0.03f
        setPadding(0, dp(18), 0, dp(6))
    })

    private fun body(text: String) = content.addView(TextView(this).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(applicationContext, R.color.tp_text_light)); textSize = 14f
        setLineSpacing(dp(3).toFloat(), 1f)
        setPadding(0, 0, 0, dp(8))
    })

    private fun bullet(text: String) = content.addView(TextView(this).apply {
        this.text = "•  $text"
        setTextColor(ContextCompat.getColor(applicationContext, R.color.tp_text_light)); textSize = 14f
        setLineSpacing(dp(3).toFloat(), 1f)
        setPadding(dp(8), 0, 0, dp(6))
    })

    /** Neutral aside — worth knowing, not a hazard. */
    private fun note(text: String) = calloutView(text, "#9AC4FF", "#14202C")

    /** Something that can bite you in the air or on the ground. */
    private fun warn(text: String) = calloutView(text, "#EF5350", "#2A1616")

    private fun calloutView(text: String, barColor: String, bgColor: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor(bgColor))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                topMargin = dp(4); bottomMargin = dp(10)
            }
        }
        row.addView(View(this).apply {
            setBackgroundColor(Color.parseColor(barColor))
            layoutParams = LinearLayout.LayoutParams(dp(3), MATCH)
        })
        row.addView(TextView(this).apply {
            this.text = text
            setTextColor(ContextCompat.getColor(applicationContext, R.color.tp_text_dim)); textSize = 13f
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        })
        content.addView(row)
    }

    private fun divider() = content.addView(View(this).apply {
        setBackgroundColor(ContextCompat.getColor(applicationContext, R.color.tp_border))
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(1)).apply {
            topMargin = dp(20); bottomMargin = dp(12)
        }
    })

    private fun spacer(heightDp: Int) = content.addView(View(this).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(heightDp))
    })

    /**
     * One control: its icon in each state worth recognising, its name, what it does, and any
     * caveats. [icons] may be empty for parts of the screen that aren't a button.
     */
    private fun entry(
        icons: List<Pair<View, String>>,
        name: String,
        what: String,
        caveats: List<String> = emptyList(),
    ) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(applicationContext, R.color.tp_surface_guide))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(10) }
        }

        if (icons.isNotEmpty()) {
            val strip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                    bottomMargin = dp(10)
                }
            }
            for ((view, caption) in icons) {
                val captionView = TextView(this@FieldGuideActivity).apply {
                    text = caption
                    setTextColor(ContextCompat.getColor(applicationContext, R.color.tp_text_muted)); textSize = 11f
                    gravity = Gravity.CENTER
                    setSingleLine(true)
                    setPadding(0, dp(5), 0, 0)
                    layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
                }
                // Size the chip from the MEASURED caption rather than leaving it to wrap_content.
                // Letting the layout work it out doesn't survive here: the icon is a fixed-width
                // child, so the cell settles on the icon's width and a longer caption
                // ("Not connected") gets silently cut to something that reads as a different
                // state ("Not conn"). Measuring the text and flooring the chip to it is the only
                // version that can't clip.
                val captionWidth = captionView.paint.measureText(caption).toInt()
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    // Toolbar-dark chip behind each example: these icons are drawn to sit on
                    // the flight toolbar, and judging them against a lighter card would be
                    // misleading about how they actually read in the air.
                    setBackgroundColor(ContextCompat.getColor(applicationContext, R.color.tp_surface_guide_code))
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    minimumWidth = captionWidth + dp(20)
                    layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply {
                        rightMargin = dp(8)
                    }
                }
                cell.addView(view)
                cell.addView(captionView)
                strip.addView(cell)
            }
            card.addView(strip)
        }

        card.addView(TextView(this).apply {
            text = name
            setTextColor(Color.WHITE); textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(5))
        })
        card.addView(TextView(this).apply {
            text = what
            setTextColor(ContextCompat.getColor(applicationContext, R.color.tp_text_light)); textSize = 13f
            setLineSpacing(dp(3).toFloat(), 1f)
        })
        for (c in caveats) {
            card.addView(TextView(this).apply {
                text = "!  $c"
                setTextColor(ContextCompat.getColor(applicationContext, R.color.tp_text_warn)); textSize = 12f
                setLineSpacing(dp(2).toFloat(), 1f)
                setPadding(0, dp(8), 0, 0)
            })
        }
        content.addView(card)
    }

    // ------------------------------------------------- live icon examples

    private fun iconParams(sizeDp: Int = 34) = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))

    private fun icon(res: Int) = image(res)

    private fun image(res: Int): View = ImageView(this).apply {
        setImageResource(res)
        scaleType = ImageView.ScaleType.FIT_CENTER
        layoutParams = iconParams()
    }

    /** The TAK badge exactly as the toolbar builds it, dot tinted to the state described. */
    private fun takBadge(connected: Boolean): View {
        val frame = android.widget.FrameLayout(this).apply { layoutParams = iconParams() }
        frame.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_tak_logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = android.widget.FrameLayout.LayoutParams(dp(34), dp(34))
        })
        frame.addView(ImageView(this).apply {
            setImageResource(R.drawable.bg_status_dot)
            setColorFilter(if (connected) CONNECTED_GREEN else DISCONNECTED_RED)
            layoutParams = android.widget.FrameLayout.LayoutParams(dp(12), dp(12)).apply {
                gravity = Gravity.BOTTOM or Gravity.START
            }
        })
        return frame
    }

    private fun battery(pct: Int): View =
        BatteryGaugeView(this).apply { layoutParams = iconParams(); setPercent(pct) }

    private fun signal(pct: Int): View =
        SignalBarsView(this).apply { layoutParams = iconParams(); setPercent(pct) }

    private fun gps(hasFix: Boolean): View = ImageView(this).apply {
        setImageResource(R.drawable.ic_gps)
        setColorFilter(if (hasFix) CONNECTED_GREEN else NO_FIX_GREY)
        layoutParams = iconParams()
    }

    private fun live(state: LiveToggleView.State): View =
        LiveToggleView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(82), dp(34))
            setState(state)
        }

    private fun rec(recording: Boolean): View =
        RecordToggleView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(74), dp(34))
            setRecording(recording)
        }

    private fun zoomPill(label: String): View = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        setBackgroundResource(R.drawable.bg_zoom_pill)
        setTextColor(Color.WHITE); textSize = 12f
        setTypeface(null, android.graphics.Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(dp(36), dp(26))
    }

    /** The AR pill in either state, built from the same drawables and tints the flight screen's
     *  refreshArButton() uses, so an example here can't show a state the toolbar never renders. */
    private fun arPill(on: Boolean): View = TextView(this).apply {
        text = "AR"
        gravity = Gravity.CENTER
        setBackgroundResource(if (on) R.drawable.bg_ar_pill_active else R.drawable.bg_zoom_pill)
        setTextColor(if (on) CONNECTED_GREEN else Color.WHITE)
        alpha = if (on) 1f else 0.45f
        textSize = 12f
        setTypeface(null, android.graphics.Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(dp(36), dp(26))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "TP2Guide"
        private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

        // Same values the flight screen tints these with, so a state shown here is the state
        // the pilot will actually see.
        private val CONNECTED_GREEN = 0xFF4CAF50.toInt()
        private val DISCONNECTED_RED = 0xFFF44336.toInt()
        private val NO_FIX_GREY = 0xFFAAAAAA.toInt()
    }
}
