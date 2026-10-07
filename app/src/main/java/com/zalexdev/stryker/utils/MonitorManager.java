package com.zalexdev.stryker.utils;

import android.content.Context;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;

import com.zalexdev.stryker.logger.Logger;
import com.zalexdev.stryker.custom.UsbDev;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;

public class MonitorManager {
    public Context context;
    public Core core;
    public Logger logger;

    public UsbDev internalUsbDev = new UsbDev("wlan0","wlan0","ip link set $ifc down; echo '4' > /sys/module/wlan/parameters/con_mode; ip link set $ifc up","ip link set $ifc down; echo '0' > /sys/module/wlan/parameters/con_mode; ip link set $ifc up");
    public UsbDev internalUsbSamsung = new UsbDev("swlan0","swlan0","ip link set $ifc down; echo '4' > /sys/module/wlan/parameters/con_mode; ip link set $ifc up","ip link set $ifc down; echo '0' > /sys/module/wlan/parameters/con_mode");

    public MonitorManager(Core core) {
        this.core = core;
        this.context = core.context;
        this.logger = core.logger;
        getDevices();
    }


    public static final String IW_PAIRS =
            "iw dev 2>/dev/null | awk '$1==\"Interface\"{n=$2} $1==\"type\"{if(n!=\"\"){print n\",\"$2; n=\"\"}}'";

    public ArrayList<String[]> listInterfaces(){
        ArrayList<String[]> out = new ArrayList<>();
        for (String t : core.customChrootCommand(IW_PAIRS, true)){
            if (t == null) continue;
            String line = t.trim();
            int c = line.indexOf(',');
            if (c <= 0 || c == line.length() - 1) continue;
            out.add(new String[]{line.substring(0, c).trim(), line.substring(c + 1).trim()});
        }
        return out;
    }

    public static boolean isInternalRadio(String ifc){
        return ifc != null && ifc.matches("(wlan0|swlan0)(mon)?");
    }

    private String baseOf(String ifc){
        return (ifc != null && ifc.endsWith("mon")) ? ifc.substring(0, ifc.length() - 3) : ifc;
    }

    private String monitorInterfaceFor(String ifc){
        if (ifc == null || ifc.isEmpty()) return null;
        String base = baseOf(ifc);
        for (String[] p : listInterfaces()){
            if (!p[1].contains("monitor")) continue;
            if (p[0].equals(ifc) || p[0].equals(base) || p[0].equals(base + "mon")) return p[0];
        }
        return null;
    }

    private boolean forceManaged(String ifc){
        core.customChrootCommand("ip link set " + ifc + " down 2>/dev/null; "
                + "iw dev " + ifc + " set type managed 2>/dev/null; "
                + "ip link set " + ifc + " up 2>/dev/null");
        return monitorInterfaceFor(ifc) == null;
    }

    public boolean disableMonitorMode(String interfaceName){
        if (core.isRootless()) {
            return disableMonitorModeRootless(interfaceName);
        }
        logger.writeLine("Disabling monitor mode on interface: " + interfaceName,1);
        String mon = monitorInterfaceFor(interfaceName);
        core.customChrootCommand(getDisableCommand(mon != null ? mon : interfaceName));
        String left = monitorInterfaceFor(interfaceName);
        boolean stillMon = left != null && !forceManaged(left);
        if (isInternalRadio(interfaceName)) {
            String base = baseOf(interfaceName);
            core.customCommand("ip link set " + base + " up; svc wifi enable");
        }
        if (stillMon) {
            logger.writeLine("Interface " + interfaceName + " is still in monitor mode", 3);
        }
        return !stillMon;
    }
    public boolean enableMonitorMode(String interfaceName){
        logger.writeLine("Enabling monitor mode on interface: " + interfaceName,1);
        if (core.isRootless()) {
            return enableMonitorModeRootless(interfaceName, null);
        }
        return enableMonitorModeRoot(interfaceName, null);
    }

    private boolean enableMonitorModeRoot(String ifc, String channel){
        if (isMonitorModeEnabled(ifc)){
            logger.writeLine("Monitor mode is already enabled on interface: " + ifc,1);
            return lockChannel(ifc, channel);
        }
        if (isInternalRadio(ifc)) {
            core.customCommand("svc wifi disable");
        }
        core.customChrootCommand(monitorCommandFor(ifc, channel));
        if (!isMonitorModeEnabled(ifc) && !isMonitorModeEnabled(ifc + "mon")
                && isInternalRadio(ifc)) {
            core.customCommand("svc wifi disable");
            core.customChrootCommand(monitorCommandFor(ifc, channel));
        }
        return lockChannel(ifc, channel);
    }

    private String monitorCommandFor(String ifc, String channel){
        return (channel == null || channel.isEmpty() || "0".equals(channel))
                ? getMonitorCommand(ifc)
                : getMonitorCommand(ifc, channel);
    }

    private boolean enableMonitorModeRootless(String ifc, String channel){
        com.zalexdev.stryker.engine.GuestEngine guest = core.guest();
        if (!guest.ensureUsbWifiAttached()) {
            logger.writeLine("No USB Wi-Fi adapter in " + guest.displayName()
                    + " — cannot enable monitor mode", 3);
            return false;
        }
        if (!isMonitorModeEnabled(ifc)) {
            StringBuilder cmd = new StringBuilder();
            cmd.append("rfkill unblock all 2>/dev/null || for f in /sys/class/rfkill/*/state; do echo 1 > \"$f\" 2>/dev/null; done; ");
            cmd.append("airmon-ng check kill >/dev/null 2>&1; ");
            cmd.append("ip link set ").append(ifc).append(" down 2>/dev/null; ");
            cmd.append("iw dev ").append(ifc).append(" set type monitor 2>/dev/null || airmon-ng start ").append(ifc).append("; ");
            cmd.append("ip link set ").append(ifc).append(" up 2>/dev/null");
            core.customChrootCommand(cmd.toString());
        }
        return lockChannel(ifc, channel);
    }

    private boolean lockChannel(String ifc, String channel){
        String monIfc = null;
        if (isMonitorModeEnabled(ifc)) monIfc = ifc;
        else if (isMonitorModeEnabled(ifc + "mon")) monIfc = ifc + "mon";
        if (monIfc == null) return false;
        if (channel != null && !channel.isEmpty() && !"0".equals(channel)) {
            core.customChrootCommand("iw dev " + monIfc + " set channel " + channel, true);
        }
        return true;
    }

    private boolean disableMonitorModeRootless(String ifc){
        logger.writeLine("Disabling monitor mode (rootless) on interface: " + ifc, 1);
        String base = baseOf(ifc);
        String mon = monitorInterfaceFor(ifc);
        String target = mon != null ? mon : ifc;
        core.customChrootCommand("airmon-ng stop " + target + " 2>/dev/null; "
                + "ip link set " + target + " down 2>/dev/null; "
                + "iw dev " + target + " set type managed 2>/dev/null; "
                + "ip link set " + target + " up 2>/dev/null; "
                + "ip link set " + base + " up 2>/dev/null");
        String left = monitorInterfaceFor(ifc);
        boolean stillMon = left != null && !forceManaged(left);
        if (stillMon) {
            logger.writeLine("Interface " + ifc + " is still in monitor mode", 3);
        }
        return !stillMon;
    }

    public boolean isMonitorModeEnabled(String interfaceName){
        if (interfaceName == null || interfaceName.isEmpty()) return false;
        for (String[] p : listInterfaces()){
            if (p[0].equals(interfaceName)) return p[1].contains("monitor");
        }
        return false;
    }
    public boolean enableMonitorMode(String interfaceName,String channel){
        logger.writeLine("Enabling monitor mode on interface: " + interfaceName+ " on channel "+channel,1);
        if (core.isRootless()) {
            return enableMonitorModeRootless(interfaceName, channel);
        }
        return enableMonitorModeRoot(interfaceName, channel);
    }

    public String getHSInterface(){
        String scanInterface = core.getString("wlan_scan");
        if (core.getInterfacesList().contains(scanInterface+"mon")){
            logger.writeLine("Scan interface is "+scanInterface+"mon",2);
            return scanInterface+"mon";
        }else{
            logger.writeLine("Scan interface is "+scanInterface,2);
            return scanInterface;
        }
    }
    public String getDeauthInterface(){
        String wlanDeauth = core.getString("wlan_deauth");
        if (core.getInterfacesList().contains(wlanDeauth+"mon")){
            logger.writeLine("Deauth interface is "+wlanDeauth+"mon",2);
            return wlanDeauth+"mon";
        }else{
            logger.writeLine("Deauth interface is "+wlanDeauth,2);
            return wlanDeauth;
        }
    }
    public String getPid(){
        String deviceId = null;
        try {

            UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
            HashMap<String, UsbDevice> devices = manager.getDeviceList();
            for (String deviceName : devices.keySet()) {
                UsbDevice device = devices.get(deviceName);
                assert device != null;
                StringBuilder string2 = new StringBuilder(Integer.toHexString(device.getVendorId()));
                while (string2.length() < 4) {
                    string2.insert(0, "0");
                }
                StringBuilder string3 = new StringBuilder(Integer.toHexString(device.getProductId()));
                while (string3.length() < 4) {
                    string3.insert(0, "0");
                }
                deviceId = string2 + ":" + string3;
            }
            return deviceId;
        } catch (Exception e) {
            return "Unknown";
        }
    }

    public ArrayList<String> getPids(){
        ArrayList<String> out = new ArrayList<>();
        try {
            UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
            for (UsbDevice device : manager.getDeviceList().values()) {
                if (device == null) continue;
                out.add(String.format(Locale.ROOT, "%04x:%04x",
                        device.getVendorId(), device.getProductId()));
            }
        } catch (Exception ignored) {}
        return out;
    }

    public void addDevice(UsbDev usbDev){
        String deviceToString = usbDev.getIfc()+"---"+usbDev.getPid()+"---"+usbDev.getCommandMon()+"---"+usbDev.getCommandDis();
        ArrayList<String> devices = core.getListString("devices");
        if (!devices.contains(deviceToString)){
            devices.add(deviceToString);
            core.putListString("devices",devices);
        }
    }
    public void addDevice(UsbDev usbDev, int index){
        String deviceToString = usbDev.getIfc()+"---"+usbDev.getPid()+"---"+usbDev.getCommandMon()+"---"+usbDev.getCommandDis();
        ArrayList<String> devices = core.getListString("devices");
        if (!devices.contains(deviceToString)){
            devices.set(index,deviceToString);
            core.putListString("devices",devices);
        }
    }
    public void removeDevice(UsbDev usbDev){
        String deviceToString = usbDev.getIfc()+"---"+usbDev.getPid()+"---"+usbDev.getCommandMon()+"---"+usbDev.getCommandDis();
        ArrayList<String> devices = core.getListString("devices");
        if (devices.contains(deviceToString)){
            devices.remove(deviceToString);
            core.putListString("devices",devices);
        }
    }
    public void removeDevice(int id){
        ArrayList<String> devices = core.getListString("devices");
        devices.remove(id);
        core.putListString("devices",devices);
    }
    public void changeDevice(UsbDev usbDev, int id){
        addDevice(usbDev,id);
    }
    public ArrayList<UsbDev> getDevices(){
        ArrayList<UsbDev> result = new ArrayList<>();
        ArrayList<String> devices = core.getListString("devices");
        if (devices.isEmpty()){
            addDevice(internalUsbDev);
            addDevice(internalUsbSamsung);
            devices = core.getListString("devices");
        }
        for (String s : devices){
            String[] l = s.split("---");
            result.add(new UsbDev(l[0],l[1],l[2],l[3]));
        }

        return result;
    }
    public boolean isDeviceAdded(String ifc){
        ArrayList<UsbDev> devices = getDevices();
        String pid = getPid();
        ArrayList<String> pids = getPids();
        if (pid==null||pid.equals("Unknown")||pid.length()<4){
        for (UsbDev d : devices){
            if (d.getIfc().equals(ifc)){
                return true;
            }
        }}else{
            for (UsbDev d : devices){
                if (d.getIfc().equals(ifc)&&pids.contains(d.getPid())){
                    return true;
                }
            }
        }
        return false;
    }
    public String getMonitorCommand(String ifc){
        ArrayList<UsbDev> devices = getDevices();
        String pid = getPid();
        ArrayList<String> pids = getPids();
        if (pid==null||pid.equals("Unknown")||pid.length()<6){
            for (UsbDev d : devices){
                if (d.getIfc().equals(ifc)){
                    core.logger.writeLine("Found device: "+d.getIfc()+" with command: "+d.getCommandMon(),2);
                    return d.getCommandMon().replace("$ch","");
                }
            }}
        else{
            for (UsbDev d : devices){
                if (d.getIfc().equals(ifc)&&pids.contains(d.getPid())){
                    core.logger.writeLine("Found device: "+d.getIfc()+" with command: "+d.getCommandMon(),2);
                    return d.getCommandMon().replace("$ch","");
                }
            }
        }
        if(ifc.equals("wlan0")){
            core.logger.writeLine("Found device: "+internalUsbDev.getIfc()+" with command: "+internalUsbDev.getCommandMon(),2);
            return internalUsbDev.getCommandMon().replace("$ch","");
        }
        if(ifc.equals("swlan0")){
            core.logger.writeLine("Found device: "+internalUsbSamsung.getIfc()+" with command: "+internalUsbSamsung.getCommandMon(),2);
            return internalUsbSamsung.getCommandMon().replace("$ch","");
        }
        return "airmon-ng start "+ifc;
    }

    public String getMonitorCommand(String ifc, String channel){
        String ch = channel == null ? "" : channel;
        ArrayList<UsbDev> devices = getDevices();
        String pid = getPid();
        ArrayList<String> pids = getPids();
        if (pid==null||pid.equals("Unknown")||pid.length()<6){
            for (UsbDev d : devices){
                if (d.getIfc().equals(ifc)){
                    return d.getCommandMon().replace("$ch",ch);
                }
            }
        }else{
            for (UsbDev d : devices){
                if (d.getIfc().equals(ifc)&&pids.contains(d.getPid())){
                    return d.getCommandMon().replace("$ch",ch);
                }
            }
        }
        if(ifc.equals("wlan0")){
            return internalUsbDev.getCommandMon().replace("$ch",ch);
        }
        if(ifc.equals("swlan0")){
            return internalUsbSamsung.getCommandMon().replace("$ch",ch);
        }
        return "airmon-ng start "+ifc + " " + ch;
    }


    public String getDisableCommand(String ifc){
        if (ifc != null && ifc.endsWith("mon") && isInternalRadio(ifc)) {
            ifc = ifc.substring(0, ifc.length() - 3);
        }
        ArrayList<UsbDev> devices = getDevices();
        String pid = getPid();
        ArrayList<String> pids = getPids();
        if (pid==null||pid.equals("Unknown")||pid.length()<6){
            for (UsbDev d : devices){
                if (d.getIfc().equals(ifc)){
                    return d.getCommandDis();
                }
            }}else{
            for (UsbDev d : devices){
                if (d.getIfc().equals(ifc)&&pids.contains(d.getPid())){
                    return d.getCommandDis();
                }
            }
        }
        if(ifc.equals("wlan0")){
            return internalUsbDev.getCommandDis();
        }
        if(ifc.equals("swlan0")){
            return internalUsbSamsung.getCommandDis();
        }
        return "airmon-ng stop "+ifc;
    }



}
