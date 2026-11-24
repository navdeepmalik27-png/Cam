CO="/system/vendor/etc/camera/camxoverridesettings.txt"
[ -f "/system/vendor/build.prop" ] && BUILDS="/system/build.prop /system/vendor/build.prop" || BUILDS="/system/build.prop"
QCP=$(grep -E "ro.board.platform=apq.*|ro.board.platform=msm.*|ro.board.platform=sdm.*" $BUILDS)
[ -z "$QCP" ] && QCP="$(cat /proc/cpuinfo | grep 'Qualcomm')"
#mi10u=$(grep -E "ro.product.vendor.name=cas.*|ro.product.board=cas.*|ro.product.vendor.name=cmi.*|ro.product.board=cmi.*" $BUILDS)
mi11=$(grep -E "ro.product.vendor.name=star.*|ro.product.board=star.*|ro.product.vendor.name=venus.*|ro.product.board=venus.*|ro.product.vendor.name=mars.*|ro.product.board=mars.*" $BUILDS)
mi12=$(grep -E "ro.product.vendor.name=zeus.*|ro.product.board=zeus.*|ro.product.vendor.name=cupid.*|ro.product.board=cupid.*|ro.product.vendor.name=psyche.*|ro.product.board=psyche.*" $BUILDS)

get_cu() {
  case "$1" in
    "-u") cat $CU | sed 's/\r$//g' | tr '\r' '\n' > $CU.tmp; mv -f $CU.tmp $CU
            for UO in $(grep "^[A-Za-z_]*=" $CU | sed -e 's/=.*//g' -e 's/Version//g'); do
              eval "$UO=$(grep_prop "$UO" $CU)"
              sed -i "s|^$UO=|$UO=$(eval echo \$$UO)|" $MODPATH/camx_user
            done;;
    *) . $CU
       for UO in $(grep "^[A-Za-z_]*=" $CU | sed -e 's/=.*//g' -e 's/CVER//g'); do
         case $UO in
           "PF"|"HJ"|"SF"|"EFB"|"AI"|"ESR"|"UPS"|"ERH"|"EG"|"ME"|"ET"|"SG"|"ST"|"MG"|"MT"|"EMW"|"WBR"|"WBG"|"WBB"|"WBT"|"EV2"|"EV2FD"|"EV2M"|"EV3"|"EV3FD"|"EV3M") case "$(eval echo \$$UO | tr '[:upper:]' '[:lower:]')" in
                                                                                                                                                 " "*) eval "$UO=";;
                                                                                                                                               esac;;
           *) case "$(eval echo \$$UO | tr '[:upper:]' '[:lower:]')" in
                "true") eval "$UO=true";;
                *) eval "$UO=false";;
              esac;;
         esac
      done;;
  esac
}

[ "$QCP" ] || abort "   This mod is suitable only for Qualcomm devices!"

if [ ! -f "$CO" ]; then 
ui_print " "
ui_print " - Camxoverride.txt doesn't exist, creating..."
ui_print " "
sleep 2
mktouch $MODPATH/$CO
else
cp_ch $CO $MODPATH/$CO
fi

CU="/storage/emulated/0/camx_user"
[ -f "$CU" ] && CVER=$(grep_prop CVER $CU)
ui_print " "
if [ -z "$CVER" ]; then
  [ -f $CU ] && ui_print "   Deprecated version of camx_user detected!" || ui_print "   ! CAMX_USER not detected !"
  ui_print "   Creating /sdcard/camx_user..."
  cp -f $MODPATH/camx_user $CU
elif [ $CVER -lt $(grep_prop CVER $MODPATH/camx_user) ]; then
  ui_print "   Older version of camx_user detected!"
  ui_print "   Updating camx_user!"
  get_cu -u
  ui_print "   Using specified options"
else
  ui_print "   Up to date camx_user detected! "
  ui_print "   Using specified options"
fi
cat $CU | sed 's/\r$//g' | tr '\r' '\n' > $CU.tmp; mv -f $CU.tmp $CU
get_cu

ui_print " "
ui_print " - Applying main patches -"
ui_print " "
ui_print " "
sleep 2
echo " " >> $MODPATH/$CO
echo "##" >> $MODPATH/$CO
echo "### UltraM8's CamXoverrides start" >> $MODPATH/$CO
echo "fovcEnable=0" >> $MODPATH/$CO
echo "enableInternalHALPixelStreamConfig=FALSE" >> $MODPATH/$CO
echo "maxHalRequests=8" >> $MODPATH/$CO
echo "enableThermalMitigation=FALSE" >> $MODPATH/$CO
echo "disableSpeckleDetection=TRUE" >> $MODPATH/$CO
echo "ifeClockFrequencyMHz=0xffffffff" >> $MODPATH/$CO
echo "ifeCamnocBandwidthMBytes=10640" >> $MODPATH/$CO
echo "ifeExternalBandwidthMBytes=10640" >> $MODPATH/$CO
echo "overrideForceFSCapable=TRUE" >> $MODPATH/$CO
echo "overrideMaxImageBufferCountRealTime=-1" >> $MODPATH/$CO
echo "overrideMaxImageBufferCountNonRealTime=-1" >> $MODPATH/$CO

ui_print " "
ui_print " - Do you want to force iHDR?"
ui_print " "
sleep 2
ui_print "   Vol Up = Set iHDR, Vol Down = Skip"
ui_print " "
if $VKSEL; then
ui_print " "
ui_print " - Force iHDR mode? -"
ui_print " "
ui_print "   iHDR is one of different in-sensor-hdr modes"
ui_print "   control should exist on most recent devices"
ui_print "   you can test it setting Force Enable"
ui_print "   if something went wrong - reflash module"
ui_print "   selecting Force Disable"
ui_print " "
sleep 3
ui_print "   Vol Up = Force Enable, Vol Down = Force Disable"
ui_print " "
if $VKSEL; then
 echo "selectIHDRUsecase=1" >> $MODPATH/$CO
 echo "enableIHDRSnapshot=1" >> $MODPATH/$CO
 echo "#8250+" >> $MODPATH/$CO
 echo "setHDRMode=1" >> $MODPATH/$CO
 echo "inSensorSHDRMode=TRUE" >> $MODPATH/$CO
 echo "IsSHDRThreeExpMode=1" >> $MODPATH/$CO
else
 echo "selectIHDRUsecase=0" >> $MODPATH/$CO
 echo "enableIHDRSnapshot=0" >> $MODPATH/$CO
 echo "#8250+" >> $MODPATH/$CO
 echo "setHDRMode=0" >> $MODPATH/$CO
 echo "inSensorSHDRMode=FALSE" >> $MODPATH/$CO
 echo "IsSHDRThreeExpMode=0" >> $MODPATH/$CO
fi
fi

if [ "$mi10u" ] || [ "$mi11" ] || [ "$mi12" ]; then
ui_print " "
ui_print " - Set 48/50mp full resolution? -"
ui_print " "
ui_print "   Your Xiaomi device is likely to support"
ui_print "   full resolution RAW without serious HAL"
ui_print "   patching. This control will expose fullres"
ui_print "   to all apps that can utilize raw stream."
ui_print "   !Beware! if you set iHDR enabled in"
ui_print "   previous option - it might interfere with"
ui_print "   full resolution"
ui_print " "
sleep 3
ui_print "   Vol Up = Enable, Vol Down = Disable"
ui_print " "
if $VKSEL; then
 echo "exposeFullSizeForQCFA=TRUE" >> $MODPATH/$CO
 echo "maxRAWSizes=3" >> $MODPATH/$CO
fi
fi

ui_print " "
ui_print " - Parsing CamX_User selectables"
ui_print " "
ui_print " "
sleep 2

if [ "$PF" ]; then
echo "perFrameSensorMode=$PF" >> $MODPATH/$CO
fi

if [ "$HJ" ]; then
echo "enableHJAF=$HJ" >> $MODPATH/$CO
fi

if [ "$SF" ]; then
echo "maxNonHfrFps=$SF" >> $MODPATH/$CO
fi

if [ $EFB == 1 ]; then
echo "enableAFB=1" >> $MODPATH/$CO
fi
if [ $EFB == 0 ]; then
echo "enableAFB=0" >> $MODPATH/$CO
fi

if [ $AI == 1 ] ; then
echo "enableAI=1" >> $MODPATH/$CO
fi
if [ $AI == 0 ]; then
echo "enableAI=0" >> $MODPATH/$CO
fi

if [ $EV2 == 1 ]; then
echo "EISV2Enable=1" >> $MODPATH/$CO
fi
if [ $EV2 == 0 ]; then
echo "EISV2Enable=0" >> $MODPATH/$CO
fi

if [ $ERH == 1 ]; then
echo "enableRawHDR=1" >> $MODPATH/$CO
fi
if [ $ERH == 0 ]; then
echo "enableRawHDR=0" >> $MODPATH/$CO
fi

if [ $ESR == 1 ]; then
echo "enableSensorRemosaic=1" >> $MODPATH/$CO
fi
if [ $ESR == 0 ]; then
echo "enableSensorRemosaic=0" >> $MODPATH/$CO
fi

if [ $UPS == 1 ]; then
echo "enableResolutionUpscale=1" >> $MODPATH/$CO
fi
if [ $UPS == 0 ]; then
echo "enableResolutionUpscale=0" >> $MODPATH/$CO
fi

if [ $EV3 == 1 ]; then
echo "EISV3Enable=1" >> $MODPATH/$CO
fi
if [ $EV3 == 0 ]; then
echo "EISV3Enable=0" >> $MODPATH/$CO
fi

if [ $EMW == 1 ]; then
echo "EISV3Enable=TRUE" >> $MODPATH/$CO
fi
if [ $EMW == 0 ]; then
echo "EISV3Enable=FALSE" >> $MODPATH/$CO
fi

if [ "$EG" ]; then
echo "exposureGain=$EG" >> $MODPATH/$CO
echo "gain=$EG" >> $MODPATH/$CO
fi
if [ "$ME" ]; then
echo "manualExposureType=$ME" >> $MODPATH/$CO
fi
if [ "$ET" ]; then
echo "sensorExposureTime=$ET" >> $MODPATH/$CO
echo "exposureTime=$ET" >> $MODPATH/$CO
fi
if [ "$SG" ]; then
echo "shortGain=$SG" >> $MODPATH/$CO
fi
if [ "$ST" ]; then
echo "sensorShortExposureTime=$ST" >> $MODPATH/$CO
fi
if [ "$MG" ]; then
echo "middleGain=$MG" >> $MODPATH/$CO
fi
if [ "$MT" ]; then
echo "sensorMiddleExposureTime=$MT" >> $MODPATH/$CO
fi

if [ "$WBR" ]; then
echo "rGain=$WBR" >> $MODPATH/$CO
fi
if [ "$WBG" ]; then
echo "gGain=$WBG" >> $MODPATH/$CO
fi
if [ "$WBB" ]; then
echo "BGain=$WBB" >> $MODPATH/$CO
fi
if [ "$WBT" ]; then
echo "colorTemp=$WBT" >> $MODPATH/$CO
fi

echo "#8150+" >> $MODPATH/$CO
echo "enablePerRequestSync=TRUE" >> $MODPATH/$CO
echo "enableNativeHEIF=TRUE" >> $MODPATH/$CO
echo "### UltraM8's CamXoverrides end" >> $MODPATH/$CO
echo "##" >> $MODPATH/$CO

ui_print " "
ui_print " "
ui_print " - Installation finished"
ui_print " "
