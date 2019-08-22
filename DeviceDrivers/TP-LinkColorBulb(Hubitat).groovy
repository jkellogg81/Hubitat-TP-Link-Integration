/*
TP-Link Device Driver, Version 4.3xxx

	Copyright 2018, 2019 Dave Gutheinz

Licensed under the Apache License, Version 2.0 (the "License"); you may not use this  file except in compliance with the
License. You may obtain a copy of the License at: http://www.apache.org/licenses/LICENSE-2.0.
Unless required by applicable law or agreed to in writing,software distributed under the License is distributed on an 
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific 
language governing permissions and limitations under the License.

DISCLAIMER:  This Applicaion and the associated Device Drivers are in no way sanctioned or supported by TP-Link.  
All  development is based upon open-source data on the TP-Link devices; primarily various users on GitHub.com.

===== History =====
2.04.19	4.1.01.	Final code for Hubitat without reference to deviceType and enhancement of logging functions.
3.28.19	4.2.01	a.	Added capability Change Level implementation.
				c.	Added user command to synchronize the Kasa App name with the Hubitat device label.
				d.	Added method updateInstallData called from app on initial update only.
7.01.19	4.3.01	a.	Updated communications architecture, reducing required logic (and error potentials).
				b.	Added import ability for driver from the HE editor.
				c.	Added preference for synching name between hub and device.  Deleted command syncKasaName.
8.25.19	4.3.02	Added comms re-transmit on FIRST time a communications doesn't succeed.  Device will
				attempt up to 5 retransmits.
================================================================================================*/
def driverVer() { return "4.3.01" }
metadata {
	definition (name: "TP-Link Color Bulb",
    			namespace: "davegut",
				author: "Dave Gutheinz",
				importUrl: "https://raw.githubusercontent.com/DaveGut/Hubitat-TP-Link-Integration/master/DeviceDrivers/TP-LinkColorBulb(Hubitat).groovy"
			   ) {
        capability "Light"
		capability "Switch"
		capability "Switch Level"
		capability "Change Level"
 		capability "Refresh"
		capability "Actuator"
		capability "Color Temperature"
		command "setCircadian"
		attribute "circadianState", "string"
		capability "Color Control"
		capability "Color Mode"
		attribute "commsError", "bool"
	}
	preferences {
		def refreshRate = [:]
		refreshRate << ["1 min" : "Refresh every minute"]
		refreshRate << ["5 min" : "Refresh every 5 minutes"]
		refreshRate << ["15 min" : "Refresh every 15 minutes"]
		refreshRate << ["30 min" : "Refresh every 30 minutes"]
		def nameMaster  = [:]
		nameMaster << ["none": "Don't synchronize"]
		nameMaster << ["device" : "Kasa (device) alias master"]
		nameMaster << ["hub" : "Hubitat label master"]
		if (!getDataValue("applicationVersion")) {
			input ("device_IP", "text", title: "Device IP (Current = ${getDataValue("deviceIP")})")
		}
		input ("refresh_Rate", "enum", title: "Device Refresh Rate", options: refreshRate, defaultValue: "30 min")
		input ("transition_Time", "num", title: "Default Transition time (seconds)", defaultValue: 0)
    	input ("highRes", "bool", title: "High Resolution Hue Scale", defaultValue: false)
		input ("debug", "bool", title: "Enable debug logging", defaultValue: false)
		input ("descriptionText", "bool", title: "Enable description text logging", defaultValue: true)
		input ("nameSync", "enum", title: "Synchronize Names", options: nameMaster, defaultValue: "none")
	}
}

def installed() {
	log.info "Installing .."
	runIn(2, updated)
}
def updated() {
	log.info "Updating .."
	unschedule()
	logInfo("Debug logging is: ${debug}.")
	logInfo("Description text logging is ${descriptionText}.")
	switch(refresh_Rate) {
		case "1 min" :
			runEvery1Minute(refresh)
			break
		case "5 min" :
			runEvery5Minutes(refresh)
			break
		case "15 min" :
			runEvery15Minutes(refresh)
			break
		default:
			runEvery30Minutes(refresh)
	}
	logInfo("Refresh set for every ${refresh_Rate} minute(s).")
	state.transTime = 1000*transition_Time.toInteger()
	if (!getDataValue("applicationVersion")) {
		logInfo("Setting deviceIP for program.")
		updateDataValue("deviceIP", device_IP)
	}
	if (getDataValue("driverVersion") != driverVer()) { updateInstallData() }
	if (getDataValue("deviceIP")) {
		if (nameSync != "none") { syncName() }
		runIn(2, refresh)
	}
}
//	Update methods called in updated
def updateInstallData() {
	logInfo("updateInstallData: Updating installation to driverVersion ${driverVer()}")
	updateDataValue("driverVersion", driverVer())
	if (getDataValue("plugId")) { updateDataValue("plugId", null) }
	if (getDataValue("plugNo")) { updateDataValue("plugNo", null) }
	if (getDataValue("hueScale")) { updateDataValue("hueScale", null) }
	state.remove("currentError")
	pauseExecution(1000)
	state.remove("commsErrorCount")
	pauseExecution(1000)
	state.remove("updated")
}
def syncName() {
	logDebug("syncName. Synchronizing device name and label with master = ${nameSync}")
	if (nameSync == "hub") {
		sendCmd("""{"smartlife.iot.common.system":{"set_dev_alias":{"alias":"${device.label}"}}}""", "nameSyncHub")
	} else if (nameSync == "device") {
		sendCmd("""{"system":{"get_sysinfo":{}}}""", "nameSyncDevice")
	}
}
def nameSyncHub(response) {
	def cmdResponse = parseInput(response)
	logInfo("Setting deviceIP for program.")
}
def nameSyncDevice(response) {
	def cmdResponse = parseInput(response)
	def alias = cmdResponse.system.get_sysinfo.alias
	device.setLabel(alias)
	logInfo("Hubit name for device changed to ${alias}.")
}


//	Device Commands
def on() {
	logDebug("On: transition time = ${state.transTime}")
	sendCmd("""{"smartlife.iot.smartbulb.lightingservice":{"transition_light_state":{"on_off":1,"transition_period":${state.transTime}}}}""", "commandResponse")
}
def off() {
	logDebug("Off: transition time = ${state.transTime}")
	sendCmd("""{"smartlife.iot.smartbulb.lightingservice":{"transition_light_state":{"on_off":0,"transition_period":${state.transTime}}}}""", "commandResponse")
}
def setLevel(percentage) {
	logDebug("setLevel(x): transition time = ${state.transTime}")
	setLevel(percentage, state.transTime)
}
def setLevel(percentage, rate) {
	logDebug("setLevel(x,x): rate = ${rate} // percentage = ${percentage}")
	if (percentage < 0 || percentage > 100) {
		logWarn("$device.name $device.label: Entered brightness is not from 0...100")
		return
	}
	percentage = percentage.toInteger()
	sendCmd("""{"smartlife.iot.smartbulb.lightingservice":{"transition_light_state":{"ignore_default":1,"on_off":1,"brightness":${percentage},"transition_period":${rate}}}}""", "commandResponse")
}
def startLevelChange(direction) {
	logDebug("startLevelChange: direction = ${direction}")
	if (direction == "up") {
		levelUp()
	} else {
		levelDown()
	}
}
def stopLevelChange() {
	logDebug("stopLevelChange")
	unschedule(levelUp)
	unschedule(levelDown)
}
def levelUp() {
	def newLevel = device.currentValue("level").toInteger() + 2
	if (newLevel > 101) { return }
	if (newLevel > 100) { newLevel = 100 }
	setLevel(newLevel, 0)
	runInMillis(500, levelUp)
}
def levelDown() {
	def newLevel = device.currentValue("level").toInteger() - 2
	if (newLevel < -1) { return }
	else if (newLevel <= 0) { off() }
	else {
		setLevel(newLevel, 0)
		runInMillis(500, levelDown)
	}
}
//	Color Temp and Color Bulb Commands
def setColorTemperature(kelvin) {
	logDebug("setColorTemperature: colorTemp = ${kelvin}")
	if (kelvin == null) kelvin = state.lastColorTemp
	if (kelvin < 2500) kelvin = 2500
	if (kelvin > 9000) kelvin = 9000
	kelvin = kelvin as int
	sendCmd("""{"smartlife.iot.smartbulb.lightingservice":{"transition_light_state":{"ignore_default":1,"on_off":1,"color_temp": ${kelvin},"hue":0,"saturation":0}}}""", "commandResponse")
}
def setCircadian() {
	logDebug("setCircadian")
	sendCmd("""{"smartlife.iot.smartbulb.lightingservice":{"transition_light_state":{"mode":"circadian"}}}""", "commandResponse")
}
def setHue(hue) {
	logDebug("setHue:  hue = ${hue} // saturation = ${state.lastSaturation}")
	saturation = state.lastSaturation
	setColor([hue: hue, saturation: saturation])
}
def setSaturation(saturation) {
	logDebug("setSaturation: saturation = ${saturation} // hue = {state.lastHue}")
	hue = state.lastHue
	setColor([hue: hue, saturation: saturation])
}
def setColor(Map color) {
	logDebug("setColor:  color = ${color}")
	if (color == null) color = [hue: state.lastHue, saturation: state.lastSaturation, level: device.currentValue("level")]
	def percentage = 100
	if (!color.level) { 
		percentage = device.currentValue("level")
	} else {
		percentage = color.level
	}
    def hue = color.hue.toInteger()
    if (highRes != true) { 
		hue = Math.round(0.5 + hue * 3.6).toInteger()
	}
	def saturation = color.saturation as int
	if (hue < 0 || hue > 360 || saturation < 0 || saturation > 100) {
		logWarn("${device.label}: Entered hue or saturation out of range!")
        return
    }
	sendCmd("""{"smartlife.iot.smartbulb.lightingservice":{"transition_light_state":{"ignore_default":1,"on_off":1,"brightness":${percentage},"color_temp":0,"hue":${hue},"saturation":${saturation}}}}""", "commandResponse")
}
def refresh(){
	logDebug("refresh")
	sendCmd("""{"system":{"get_sysinfo":{}}}""", "refreshResponse")
}
//	Device command parsing methods
def commandResponse(response) {
	def cmdResponse = parseInput(response)
	def status = cmdResponse["smartlife.iot.smartbulb.lightingservice"].transition_light_state
	logDebug("commandResponse: status = ${status}")
	updateBulbData(status)
}
def refreshResponse(response) {
	def cmdResponse = parseInput(response)
	def status = cmdResponse.system.get_sysinfo.light_state
	logDebug("refreshResponse: status = ${status}")
	updateBulbData(status)
}
def updateBulbData(status) {
	if (state.previousStatus == status) { return }
	state.previousStatus = status
	if (status.on_off == 0) {
		sendEvent(name: "switch", value: "off")
		logInfo("Power: off")
		sendEvent(name: "circadianState", value: "normal")
	} else {
		sendEvent(name: "switch", value: "on")
		sendEvent(name: "level", value: status.brightness)
		def color = [:]
		def hue = status.hue.toInteger()
		if (highRes != true) { hue = (hue / 3.6).toInteger() }
		color << ["hue" : hue]
		color << ["saturation" : status.saturation]
		color << ["level" : status.brightness]
		sendEvent(name: "circadianState", value: status.mode)
		sendEvent(name: "colorTemperature", value: status.color_temp)
		sendEvent(name: "hue", value: hue)
		sendEvent(name: "saturation", value: status.saturation)
		sendEvent(name: "color", value: color)
		logInfo("Power: on / Brightness: ${status.brightness}% / " +
				 "Circadian State: ${status.mode} / Color Temp: " +
				 "${status.color_temp}K / Color: ${color}")
		if (status.color_temp.toInteger() == 0) { setRgbData(hue, status.saturation) }
		else { setColorTempData(status.color_temp) }
	}
}
def setColorTempData(temp){
	logDebug("setColorTempData: color temperature = ${temp}")
    def value = temp.toInteger()
	state.lastColorTemp = value
    def genericName
	if (value <= 2800) { genericName = "Incandescent" }
	else if (value <= 3300) { genericName = "Soft White" }
	else if (value <= 3500) { genericName = "Warm White" }
	else if (value <= 4150) { genericName = "Moonlight" }
	else if (value <= 5000) { genericName = "Horizon" }
	else if (value <= 5500) { genericName = "Daylight" }
	else if (value <= 6000) { genericName = "Electronic" }
	else if (value <= 6500) { genericName = "Skylight" }
	else { genericName = "Polar" }
	logInfo "${device.getDisplayName()} Color Mode is CT.  Color is ${genericName}."
 	sendEvent(name: "colorMode", value: "CT")
    sendEvent(name: "colorName", value: genericName)
}
def setRgbData(hue, saturation){
	logDebug("setRgbData: hue = ${hue} // highRes = ${highRes}")
    def colorName
    hue = hue.toInteger()
	state.lastHue = hue
	state.lastSaturation = saturation
	if (highRes != true) { hue = (hue * 3.6).toInteger() }
    switch (hue.toInteger()){
		case 0..15: colorName = "Red"
            break
		case 16..45: colorName = "Orange"
            break
		case 46..75: colorName = "Yellow"
            break
		case 76..105: colorName = "Chartreuse"
            break
		case 106..135: colorName = "Green"
            break
		case 136..165: colorName = "Spring"
            break
		case 166..195: colorName = "Cyan"
            break
		case 196..225: colorName = "Azure"
            break
		case 226..255: colorName = "Blue"
            break
		case 256..285: colorName = "Violet"
            break
		case 286..315: colorName = "Magenta"
            break
		case 316..345: colorName = "Rose"
            break
		case 346..360: colorName = "Red"
            break
    }
	logInfo "${device.getDisplayName()} Color Mode is RGB.  Color is ${colorName}."
 	sendEvent(name: "colorMode", value: "RGB")
    sendEvent(name: "colorName", value: colorName)
}


//	Communications and initial common parsing
private sendCmd(command, action) {
	logDebug("sendCmd: command = ${command} // device IP = ${getDataValue("deviceIP")}, action = ${action}")
	state.lastCommand = command
	state.lastAction = action
	runIn(3, setCommsError)
	def myHubAction = new hubitat.device.HubAction(
		outputXOR(command),
		hubitat.device.Protocol.LAN,
		[type: hubitat.device.HubAction.Type.LAN_TYPE_UDPCLIENT,
		 destinationAddress: "${getDataValue("deviceIP")}:9999",
		 encoding: hubitat.device.HubAction.Encoding.HEX_STRING,
		 timeout: 3,
		 callback: action])
	sendHubCommand(myHubAction)
}
def parseInput(response) {
	unschedule(setCommsError)
	state.errorCount = 0
	sendEvent(name: "commsError", value: false)
	try {
		def encrResponse = parseLanMessage(response).payload
		def cmdResponse = parseJson(inputXOR(encrResponse))
		return cmdResponse
	} catch (error) {
		logWarn "CommsError: Fragmented message returned from device."
	}
}
def setCommsError() {
	logDebug("setCommsError")
	if (state.errorCount < 5) {
		state.errorCount+= 1
		sendCmd(state.lastCommand, state.lastAction)
		logWarn("Attempt ${state.errorCount} to recover communications")
	} else {
		sendEvent(name: "commsError", value: true)
		logWarn "CommsError: No response from device.  Refresh.  If off line " +
				"persists, check IP address of device."
	}
}


//	Utility Methods
private outputXOR(command) {
	def str = ""
	def encrCmd = ""
 	def key = 0xAB
	for (int i = 0; i < command.length(); i++) {
		str = (command.charAt(i) as byte) ^ key
		key = str
		encrCmd += Integer.toHexString(str)
	}
   	return encrCmd
}
private inputXOR(encrResponse) {
	String[] strBytes = encrResponse.split("(?<=\\G.{2})")
	def cmdResponse = ""
	def key = 0xAB
	def nextKey
	byte[] XORtemp
	for(int i = 0; i < strBytes.length; i++) {
		nextKey = (byte)Integer.parseInt(strBytes[i], 16)	// could be negative
		XORtemp = nextKey ^ key
		key = nextKey
		cmdResponse += new String(XORtemp)
	}
	return cmdResponse
}
def logInfo(msg) {
	if (descriptionText == true) { log.info "${device.label} ${driverVer()} ${msg}" }
}
def logDebug(msg){
	if(debug == true) { log.debug "${device.label} ${driverVer()} ${msg}" }
}
def logWarn(msg){ log.warn "${device.label} ${driverVer()} ${msg}" }

//	end-of-file