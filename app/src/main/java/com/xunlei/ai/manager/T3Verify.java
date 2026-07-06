package com.xunlei.ai.manager;

import android.util.Base64;

import java.io.*;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.NetworkInterface;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.*;
import javax.crypto.Cipher;

/**
 * T3验证SDK - Android版本
 * 官网: https://www.t3yanzheng.com
 */
public class T3Verify {

    private static final String SERVER_URL = "https://w.t3yanzheng.com/";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private String serverUrl = SERVER_URL;
    private String loginCode, noticeCode, versionCode, heartbeatCode;
    private String queryCode, registerCode, userLoginCode, userHeartbeatCode;
    private String qqLoginCode, bindQQCode, changePasswordCode, userCancelCode;
    private String rechargeCode, unbindCode, ipUnbindCode, disableCode;
    private String checkUpdateCode, getVariableCode, modifyVariableCode;
    private String modifyCoreCode, getKamiCoreCode, getUserCoreCode;
    private String onlineKamiCode, onlineUserCode, cloudDocCode, appSignCode;
    private String appkey, encodeType = "base64", statecode, endTime;
    private CustomBase64 encoder;
    private RSACrypto rsaCrypto;

    // ============================================================
    // 结果类
    // ============================================================
    public static class T3Result {
        public boolean success; public String error, msg;
        public static T3Result ok(String msg) { T3Result r = new T3Result(); r.success = true; r.msg = msg; return r; }
        public static T3Result fail(String error) { T3Result r = new T3Result(); r.success = false; r.error = error; return r; }
    }

    public static class T3LoginResult extends T3Result {
        public String id, endTime, statecode, recharge, useTime, amount, available, imei, change, core;
        public static T3LoginResult ok(String id, String endTime, String statecode) {
            T3LoginResult r = new T3LoginResult(); r.success = true; r.id = id; r.endTime = endTime; r.statecode = statecode; return r;
        }
        public static T3LoginResult fail(String error) { T3LoginResult r = new T3LoginResult(); r.success = false; r.error = error; return r; }
    }

    public static class T3NoticeResult extends T3Result {
        public String notice;
        public static T3NoticeResult ok(String notice) { T3NoticeResult r = new T3NoticeResult(); r.success = true; r.notice = notice; return r; }
        public static T3NoticeResult fail(String error) { T3NoticeResult r = new T3NoticeResult(); r.success = false; r.error = error; return r; }
    }

    public static class T3VersionResult extends T3Result {
        public String version;
        public static T3VersionResult ok(String version) { T3VersionResult r = new T3VersionResult(); r.success = true; r.version = version; return r; }
        public static T3VersionResult fail(String error) { T3VersionResult r = new T3VersionResult(); r.success = false; r.error = error; return r; }
    }

    public static class T3QueryResult extends T3Result {
        public String state, use, id, useTime, endTime, lineTime, line, amount, available;
        public static T3QueryResult fail(String error) { T3QueryResult r = new T3QueryResult(); r.success = false; r.error = error; return r; }
    }

    public static class T3UpdateResult extends T3Result {
        public boolean hasUpdate; public String ver, version, uplog, upurl;
        public static T3UpdateResult fail(String error) { T3UpdateResult r = new T3UpdateResult(); r.success = false; r.error = error; return r; }
    }

    public static class T3VariableResult extends T3Result {
        public String value;
        public static T3VariableResult ok(String value) { T3VariableResult r = new T3VariableResult(); r.success = true; r.value = value; return r; }
        public static T3VariableResult fail(String error) { T3VariableResult r = new T3VariableResult(); r.success = false; r.error = error; return r; }
    }

    public static class T3CloudDocResult extends T3Result {
        public String content;
        public static T3CloudDocResult ok(String content) { T3CloudDocResult r = new T3CloudDocResult(); r.success = true; r.content = content; return r; }
        public static T3CloudDocResult fail(String error) { T3CloudDocResult r = new T3CloudDocResult(); r.success = false; r.error = error; return r; }
    }

    public static class T3CoreResult extends T3Result {
        public String core;
        public static T3CoreResult ok(String core) { T3CoreResult r = new T3CoreResult(); r.success = true; r.core = core; return r; }
        public static T3CoreResult fail(String error) { T3CoreResult r = new T3CoreResult(); r.success = false; r.error = error; return r; }
    }

    public static class T3OnlineResult extends T3Result {
        public int count;
        public static T3OnlineResult ok(int count) { T3OnlineResult r = new T3OnlineResult(); r.success = true; r.count = count; return r; }
        public static T3OnlineResult fail(String error) { T3OnlineResult r = new T3OnlineResult(); r.success = false; r.error = error; return r; }
    }

    public static class T3AppSignResult extends T3Result {
        public String autograph; public Long time;
        public static T3AppSignResult fail(String error) { T3AppSignResult r = new T3AppSignResult(); r.success = false; r.error = error; return r; }
    }

    // ============================================================
    // CustomBase64
    // ============================================================
    private static class CustomBase64 {
        private static final String STD = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        private final String custom;
        public CustomBase64(String custom) { this.custom = custom; }
        public String encode(String data) {
            String std = Base64.encodeToString(data.getBytes(UTF8), Base64.NO_WRAP);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < std.length(); i++) {
                char c = std.charAt(i);
                int idx = STD.indexOf(c);
                sb.append(idx != -1 ? custom.charAt(idx) : c);
            }
            return sb.toString();
        }
        public String decode(String data) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < data.length(); i++) {
                char c = data.charAt(i);
                int idx = custom.indexOf(c);
                sb.append(idx != -1 ? STD.charAt(idx) : c);
            }
            return new String(Base64.decode(sb.toString(), Base64.NO_WRAP), UTF8);
        }
        public String encodeToHex(String data) { return bytesToHex(encode(data).getBytes(UTF8)); }
    }

    // ============================================================
    // RSACrypto
    // ============================================================
    private static class RSACrypto {
        private final PublicKey publicKey;
        private final int keySize, encryptBlockSize, decryptBlockSize;
        public RSACrypto(String publicKeyPem) throws Exception {
            String pem = publicKeyPem.trim();
            if (!pem.startsWith("-----BEGIN")) pem = "-----BEGIN PUBLIC KEY-----\n" + pem + "\n-----END PUBLIC KEY-----";
            String b64 = pem.replaceAll("-----BEGIN PUBLIC KEY-----", "")
                    .replaceAll("-----END PUBLIC KEY-----", "").replaceAll("\\s+", "");
            byte[] keyBytes = Base64.decode(b64, Base64.NO_WRAP);
            this.publicKey = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(keyBytes));
            this.keySize = keyBytes.length > 200 ? 256 : 128;
            this.encryptBlockSize = this.keySize - 11;
            this.decryptBlockSize = this.keySize;
        }
        public byte[] encrypt(String data) throws Exception {
            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.ENCRYPT_MODE, publicKey);
            byte[] dataBytes = data.getBytes(UTF8);
            ByteArrayOutputStream os = new ByteArrayOutputStream();
            int offset = 0;
            while (offset < dataBytes.length) {
                int len = Math.min(encryptBlockSize, dataBytes.length - offset);
                byte[] block = new byte[len]; System.arraycopy(dataBytes, offset, block, 0, len);
                os.write(cipher.doFinal(block));
                offset += len;
            }
            return os.toByteArray();
        }
        public String decrypt(byte[] encryptedData) throws Exception {
            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.DECRYPT_MODE, publicKey);
            ByteArrayOutputStream os = new ByteArrayOutputStream();
            int offset = 0;
            while (offset < encryptedData.length) {
                int len = Math.min(decryptBlockSize, encryptedData.length - offset);
                byte[] block = new byte[len]; System.arraycopy(encryptedData, offset, block, 0, len);
                os.write(cipher.doFinal(block));
                offset += decryptBlockSize;
            }
            return os.toString("UTF-8");
        }
        public String encryptToHex(String data) throws Exception { return bytesToHex(encrypt(data)); }
        public String decryptFromBase64(String b64) throws Exception { return decrypt(Base64.decode(b64, Base64.NO_WRAP)); }
    }

    // ============================================================
    // 工具方法
    // ============================================================
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02X", b & 0xFF));
        return sb.toString();
    }

    private static String md5(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(input.getBytes(UTF8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b & 0xFF));
            return sb.toString();
        } catch (Exception e) { throw new RuntimeException("MD5计算失败", e); }
    }

    private static Map<String, Object> parseJson(String json) {
        Map<String, Object> map = new LinkedHashMap<>();
        json = json.trim();
        if (json.startsWith("{")) json = json.substring(1);
        if (json.endsWith("}")) json = json.substring(0, json.length() - 1);
        int i = 0;
        while (i < json.length()) {
            while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
            if (i >= json.length()) break;
            if (json.charAt(i) != '"') { i++; continue; }
            int keyStart = ++i;
            while (i < json.length() && json.charAt(i) != '"') i++;
            String key = json.substring(keyStart, i); i++;
            while (i < json.length() && json.charAt(i) != ':') i++; i++;
            while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
            if (i >= json.length()) break;
            char c = json.charAt(i);
            if (c == '"') {
                int vs = ++i;
                StringBuilder sb = new StringBuilder();
                while (i < json.length() && json.charAt(i) != '"') {
                    if (json.charAt(i) == '\\' && i + 1 < json.length()) {
                        i++; char esc = json.charAt(i);
                        if (esc == '"') sb.append('"'); else if (esc == '\\') sb.append('\\');
                        else if (esc == 'n') sb.append('\n'); else if (esc == 't') sb.append('\t');
                        else if (esc == 'r') sb.append('\r'); else if (esc == '/') sb.append('/');
                        else if (esc == 'u' && i + 4 < json.length()) { sb.append((char)Integer.parseInt(json.substring(i+1,i+5),16)); i+=4; }
                        else sb.append(esc);
                    } else sb.append(json.charAt(i));
                    i++;
                }
                map.put(key, sb.toString()); i++;
            } else if (c == '-' || Character.isDigit(c)) {
                int vs = i; boolean isFloat = false;
                while (i < json.length() && (Character.isDigit(json.charAt(i)) || json.charAt(i) == '-' || json.charAt(i) == '.')) {
                    if (json.charAt(i) == '.') isFloat = true; i++;
                }
                String numStr = json.substring(vs, i);
                map.put(key, isFloat ? (Object)Double.parseDouble(numStr) : (Object)Long.parseLong(numStr));
            } else if (c == 't' || c == 'f') { map.put(key, c == 't'); i += (c == 't' ? 4 : 5); }
            else if (c == 'n') { map.put(key, null); i += 4; }
            while (i < json.length() && json.charAt(i) != ',' && json.charAt(i) != '}') i++;
            if (i < json.length() && json.charAt(i) == ',') i++;
        }
        return map;
    }

    private static String getStr(Map<String, Object> m, String k) { Object v = m.get(k); return v != null ? v.toString() : null; }
    private static int getCode(Map<String, Object> m) { Object v = m.get("code"); return v instanceof Number ? ((Number)v).intValue() : -1; }

    public static String getMachineCode() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                byte[] mac = interfaces.nextElement().getHardwareAddress();
                if (mac != null && mac.length > 0) {
                    StringBuilder macStr = new StringBuilder();
                    for (byte b : mac) macStr.append(String.format("%02x", b & 0xFF));
                    return md5(macStr.toString()).toUpperCase();
                }
            }
        } catch (Exception e) { }
        return md5(String.valueOf(System.currentTimeMillis())).toUpperCase();
    }

    // ============================================================
    // 初始化
    // ============================================================
    public void init(String loginCode, String noticeCode, String versionCode,
                     String heartbeatCode, String appkey, String base64Charset) {
        this.loginCode = loginCode; this.noticeCode = noticeCode;
        this.versionCode = versionCode; this.heartbeatCode = heartbeatCode;
        this.appkey = appkey; this.encodeType = "base64";
        if (base64Charset == null || base64Charset.isEmpty()) throw new IllegalArgumentException("Base64模式下必须提供 base64Charset 参数");
        this.encoder = new CustomBase64(base64Charset);
    }

    public void initRsa(String loginCode, String noticeCode, String versionCode,
                        String heartbeatCode, String appkey, String rsaPublicKey) {
        this.loginCode = loginCode; this.noticeCode = noticeCode;
        this.versionCode = versionCode; this.heartbeatCode = heartbeatCode;
        this.appkey = appkey; this.encodeType = "rsa";
        if (rsaPublicKey == null || rsaPublicKey.isEmpty()) throw new IllegalArgumentException("RSA模式下必须提供 rsaPublicKey 参数");
        try { this.rsaCrypto = new RSACrypto(rsaPublicKey); }
        catch (Exception e) { throw new RuntimeException("初始化RSA加密器失败: " + e.getMessage(), e); }
    }

    public void setQueryCode(String code) { this.queryCode = code; }
    public void setRegisterCode(String code) { this.registerCode = code; }
    public void setUserLoginCode(String code) { this.userLoginCode = code; }
    public void setUserHeartbeatCode(String code) { this.userHeartbeatCode = code; }
    public void setQqLoginCode(String code) { this.qqLoginCode = code; }
    public void setBindQQCode(String code) { this.bindQQCode = code; }
    public void setChangePasswordCode(String code) { this.changePasswordCode = code; }
    public void setUserCancelCode(String code) { this.userCancelCode = code; }
    public void setRechargeCode(String code) { this.rechargeCode = code; }
    public void setUnbindCode(String code) { this.unbindCode = code; }
    public void setIpUnbindCode(String code) { this.ipUnbindCode = code; }
    public void setDisableCode(String code) { this.disableCode = code; }
    public void setCheckUpdateCode(String code) { this.checkUpdateCode = code; }
    public void setGetVariableCode(String code) { this.getVariableCode = code; }
    public void setModifyVariableCode(String code) { this.modifyVariableCode = code; }
    public void setModifyCoreCode(String code) { this.modifyCoreCode = code; }
    public void setGetKamiCoreCode(String code) { this.getKamiCoreCode = code; }
    public void setGetUserCoreCode(String code) { this.getUserCoreCode = code; }
    public void setOnlineKamiCode(String code) { this.onlineKamiCode = code; }
    public void setOnlineUserCode(String code) { this.onlineUserCode = code; }
    public void setCloudDocCode(String code) { this.cloudDocCode = code; }
    public void setAppSignCode(String code) { this.appSignCode = code; }

    // ============================================================
    // 内部方法
    // ============================================================
    private void checkInit() { if (appkey == null) throw new IllegalStateException("未初始化，请先调用 init() 或 initRsa() 方法"); }
    private void checkCode(String code, String name) { if (code == null || code.isEmpty()) throw new IllegalStateException("未设置 " + name + " 调用码"); }
    private String buildUrl(String code) { return serverUrl.endsWith("/") ? serverUrl + code : serverUrl + "/" + code; }

    private String encodeValue(String value) {
        try { return "base64".equals(encodeType) ? encoder.encodeToHex(value) : rsaCrypto.encryptToHex(value); }
        catch (Exception e) { throw new RuntimeException("编码失败: " + e.getMessage(), e); }
    }

    private String decodeResponse(String responseText) {
        try { return "base64".equals(encodeType) ? encoder.decode(responseText) : rsaCrypto.decryptFromBase64(responseText); }
        catch (Exception e) { throw new RuntimeException("响应解码失败: " + e.getMessage(), e); }
    }

    private String[] encodeParams(LinkedHashMap<String, String> params) {
        LinkedHashMap<String, String> encoded = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : params.entrySet()) encoded.put(e.getKey(), encodeValue(e.getValue()));
        StringBuilder sStr = new StringBuilder(); boolean first = true;
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!first) sStr.append("&"); sStr.append(e.getKey()).append("=").append(encoded.get(e.getKey())); first = false;
        }
        sStr.append("&").append(appkey);
        encoded.put("s", encodeValue(md5(sStr.toString())));
        StringBuilder postData = new StringBuilder(); first = true;
        for (Map.Entry<String, String> e : encoded.entrySet()) {
            if (!first) postData.append("&");
            try { postData.append(URLEncoder.encode(e.getKey(),"UTF-8")).append("=").append(URLEncoder.encode(e.getValue(),"UTF-8")); }
            catch (Exception ex) { postData.append(e.getKey()).append("=").append(e.getValue()); }
            first = false;
        }
        return new String[]{postData.toString(), sStr.toString()};
    }

    private String httpPost(String urlStr, String postData) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("POST"); conn.setDoOutput(true);
            conn.setConnectTimeout(10000); conn.setReadTimeout(10000);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            OutputStream os = conn.getOutputStream(); os.write(postData.getBytes(UTF8)); os.close();
            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), UTF8));
            StringBuilder response = new StringBuilder(); String line;
            while ((line = reader.readLine()) != null) response.append(line);
            reader.close(); return response.toString();
        } finally { if (conn != null) conn.disconnect(); }
    }

    private T3Result simpleRequest(String code, String codeName, LinkedHashMap<String, String> params) {
        try {
            checkInit(); checkCode(code, codeName);
            params.put("t", String.valueOf(System.currentTimeMillis() / 1000));
            String[] encoded = encodeParams(params);
            String resp; try { resp = httpPost(buildUrl(code), encoded[0]); } catch (IOException e) { return T3Result.fail("请求失败: " + e.getMessage()); }
            String decoded; try { decoded = decodeResponse(resp); } catch (Exception e) { return T3Result.fail("响应解码失败: " + e.getMessage()); }
            Map<String, Object> json; try { json = parseJson(decoded); } catch (Exception e) { return T3Result.fail("响应不是有效的JSON格式"); }
            int statusCode = getCode(json);
            if (statusCode != 200 && !"200".equals(getStr(json, "code"))) return T3Result.fail(getStr(json, "msg") != null ? getStr(json, "msg") : "未知错误");
            return T3Result.ok(getStr(json, "msg") != null ? getStr(json, "msg") : "");
        } catch (Exception e) { return T3Result.fail(e.getMessage()); }
    }

    private LinkedHashMap<String, String> map(String... kv) {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i+1]);
        return m;
    }

    // ============================================================
    // 卡密验证
    // ============================================================
    public T3LoginResult login(String kami, String imei) {
        try {
            checkInit(); checkCode(loginCode, "单码登录");
            long t = System.currentTimeMillis() / 1000;
            String[] encoded = encodeParams(map("kami", kami, "imei", imei, "t", String.valueOf(t)));
            String resp; try { resp = httpPost(buildUrl(loginCode), encoded[0]); } catch (IOException e) { return T3LoginResult.fail("请求失败: " + e.getMessage()); }
            String decoded; try { decoded = decodeResponse(resp); } catch (Exception e) { return T3LoginResult.fail("响应解码失败: " + e.getMessage()); }
            Map<String, Object> json; try { json = parseJson(decoded); } catch (Exception e) { return T3LoginResult.fail("响应不是有效的JSON格式"); }
            if (getCode(json) != 200) return T3LoginResult.fail(getStr(json, "msg") != null ? getStr(json, "msg") : "未知错误");
            String id = getStr(json,"id"), et = getStr(json,"end_time"), tk = getStr(json,"token"), sc = getStr(json,"statecode");
            if (id == null || et == null || tk == null || sc == null) return T3LoginResult.fail("响应数据缺少必要字段");
            Object timeObj = json.get("time"); long rt = timeObj instanceof Number ? ((Number)timeObj).longValue() : 0;
            if (Math.abs(System.currentTimeMillis()/1000 - rt) > 5) return T3LoginResult.fail("时间戳校验失败");
            String dateStr = new SimpleDateFormat("yyyyMMddHHmm").format(new Date());
            if (!tk.toLowerCase().equals(md5(id + appkey + encoded[1] + et + dateStr))) return T3LoginResult.fail("token校验失败");
            this.statecode = sc; this.endTime = et;
            T3LoginResult r = T3LoginResult.ok(id, et, sc);
            r.recharge = getStr(json,"recharge"); r.useTime = getStr(json,"use_time");
            r.available = getStr(json,"available"); r.imei = getStr(json,"imei");
            r.change = getStr(json,"change"); r.core = getStr(json,"core");
            r.amount = getStr(json,"amount");
            return r;
        } catch (Exception e) { return T3LoginResult.fail(e.getMessage()); }
    }

    public T3QueryResult queryKami(String kami) {
        try {
            checkInit(); checkCode(queryCode, "查询卡密");
            String[] encoded = encodeParams(map("kami", kami, "t", String.valueOf(System.currentTimeMillis()/1000)));
            String resp; try { resp = httpPost(buildUrl(queryCode), encoded[0]); } catch (IOException e) { return T3QueryResult.fail("请求失败: " + e.getMessage()); }
            String decoded; try { decoded = decodeResponse(resp); } catch (Exception e) { return T3QueryResult.fail("响应解码失败: " + e.getMessage()); }
            Map<String, Object> json; try { json = parseJson(decoded); } catch (Exception e) { return T3QueryResult.fail("响应不是有效的JSON格式"); }
            if (getCode(json) != 200) return T3QueryResult.fail(getStr(json, "msg") != null ? getStr(json, "msg") : "未知错误");
            T3QueryResult r = new T3QueryResult(); r.success = true;
            r.state = getStr(json,"state"); r.use = getStr(json,"use"); r.id = getStr(json,"id");
            r.useTime = getStr(json,"use_time"); r.endTime = getStr(json,"end_time");
            r.lineTime = getStr(json,"line_time"); r.line = getStr(json,"line");
            r.amount = getStr(json,"amount"); r.available = getStr(json,"available");
            return r;
        } catch (Exception e) { return T3QueryResult.fail(e.getMessage()); }
    }

    public T3Result heartbeat(String kami, String statecode) {
        return simpleRequest(heartbeatCode, "单码心跳", map("kami", kami, "statecode", statecode));
    }

    // ============================================================
    // 数据与内容
    // ============================================================
    public T3NoticeResult getNotice() {
        try {
            checkInit(); checkCode(noticeCode, "公告");
            String[] encoded = encodeParams(map("t", String.valueOf(System.currentTimeMillis()/1000)));
            String resp = httpPost(buildUrl(noticeCode), encoded[0]);
            String decoded = decodeResponse(resp);
            Map<String, Object> json = parseJson(decoded);
            if (getCode(json) != 200) return T3NoticeResult.fail(getStr(json,"msg") != null ? getStr(json,"msg") : "未知错误");
            return T3NoticeResult.ok(getStr(json,"msg") != null ? getStr(json,"msg") : "");
        } catch (Exception e) { return T3NoticeResult.fail(e.getMessage()); }
    }

    public T3VersionResult getLatestVersion() {
        try {
            checkInit(); checkCode(versionCode, "版本号");
            String[] encoded = encodeParams(map("t", String.valueOf(System.currentTimeMillis()/1000)));
            String resp = httpPost(buildUrl(versionCode), encoded[0]);
            String decoded = decodeResponse(resp);
            Map<String, Object> json = parseJson(decoded);
            if (getCode(json) != 200) return T3VersionResult.fail(getStr(json,"msg") != null ? getStr(json,"msg") : "未知错误");
            return T3VersionResult.ok(getStr(json,"msg") != null ? getStr(json,"msg") : "");
        } catch (Exception e) { return T3VersionResult.fail(e.getMessage()); }
    }

    public T3UpdateResult checkUpdate(String ver) {
        try {
            checkInit(); checkCode(checkUpdateCode, "检查更新");
            String[] encoded = encodeParams(map("ver", ver, "t", String.valueOf(System.currentTimeMillis()/1000)));
            String resp = httpPost(buildUrl(checkUpdateCode), encoded[0]);
            String decoded = decodeResponse(resp);
            Map<String, Object> json = parseJson(decoded);
            int code = getCode(json);
            if (code == 200) {
                T3UpdateResult r = new T3UpdateResult(); r.success = true; r.hasUpdate = true;
                r.ver = getStr(json,"ver"); r.version = getStr(json,"version");
                r.uplog = getStr(json,"uplog"); r.upurl = getStr(json,"upurl"); return r;
            } else if (code == 201) {
                T3UpdateResult r = new T3UpdateResult(); r.success = true; r.hasUpdate = false;
                r.msg = getStr(json,"msg") != null ? getStr(json,"msg") : "已是最新版"; return r;
            }
            return T3UpdateResult.fail(getStr(json,"msg") != null ? getStr(json,"msg") : "未知错误");
        } catch (Exception e) { return T3UpdateResult.fail(e.getMessage()); }
    }

    public T3CloudDocResult getCloudDoc(String token) {
        T3Result r = simpleRequest(cloudDocCode, "云文档", map("token", token));
        if (r.success) return T3CloudDocResult.ok(r.msg);
        return T3CloudDocResult.fail(r.error);
    }

    public T3AppSignResult appSign(String autograph) {
        try {
            checkInit(); checkCode(appSignCode, "应用签名");
            long t = System.currentTimeMillis() / 1000;
            String[] encoded = encodeParams(map("autograph", autograph, "t", String.valueOf(t)));
            String resp; try { resp = httpPost(buildUrl(appSignCode), encoded[0]); } catch (IOException e) { return T3AppSignResult.fail("请求失败: " + e.getMessage()); }
            String decoded; try { decoded = decodeResponse(resp); } catch (Exception e) { return T3AppSignResult.fail("响应解码失败: " + e.getMessage()); }
            Map<String, Object> json; try { json = parseJson(decoded); } catch (Exception e) { return T3AppSignResult.fail("响应不是有效的JSON格式"); }
            int code = getCode(json);
            if (code != 200 && !"200".equals(getStr(json, "code"))) return T3AppSignResult.fail(getStr(json, "msg") != null ? getStr(json, "msg") : "未知错误");
            T3AppSignResult r = new T3AppSignResult(); r.success = true;
            r.msg = getStr(json, "msg"); r.autograph = getStr(json, "autograph");
            Object timeObj = json.get("time"); r.time = timeObj instanceof Number ? ((Number)timeObj).longValue() : null;
            return r;
        } catch (Exception e) { return T3AppSignResult.fail(e.getMessage()); }
    }

    // ============================================================
    // 用户体系
    // ============================================================
    public T3Result userRegister(String user, String pass) { return simpleRequest(registerCode, "用户注册", map("user", user, "pass", pass)); }
    public T3Result userRegister(String user, String pass, String email) { return simpleRequest(registerCode, "用户注册", map("user", user, "pass", pass, "email", email)); }
    public T3LoginResult userLogin(String user, String pass, String imei) {
        try {
            checkInit(); checkCode(userLoginCode, "用户登录");
            long t = System.currentTimeMillis() / 1000;
            String[] encoded = encodeParams(map("user", user, "pass", pass, "imei", imei, "t", String.valueOf(t)));
            String resp; try { resp = httpPost(buildUrl(userLoginCode), encoded[0]); } catch (IOException e) { return T3LoginResult.fail("请求失败: " + e.getMessage()); }
            String decoded; try { decoded = decodeResponse(resp); } catch (Exception e) { return T3LoginResult.fail("响应解码失败: " + e.getMessage()); }
            Map<String, Object> json; try { json = parseJson(decoded); } catch (Exception e) { return T3LoginResult.fail("响应不是有效的JSON格式"); }
            if (getCode(json) != 200) return T3LoginResult.fail(getStr(json,"msg") != null ? getStr(json,"msg") : "未知错误");
            T3LoginResult r = new T3LoginResult(); r.success = true;
            r.id = getStr(json,"id"); r.endTime = getStr(json,"end_time"); r.statecode = getStr(json,"statecode");
            r.recharge = getStr(json,"recharge"); r.useTime = getStr(json,"use_time");
            r.available = getStr(json,"available"); r.imei = getStr(json,"imei");
            r.change = getStr(json,"change"); r.core = getStr(json,"core");
            this.statecode = r.statecode; this.endTime = r.endTime;
            return r;
        } catch (Exception e) { return T3LoginResult.fail(e.getMessage()); }
    }
    public T3LoginResult qqLogin(String openid, String accessToken) {
        try {
            checkInit(); checkCode(qqLoginCode, "QQ登录");
            long t = System.currentTimeMillis() / 1000;
            String[] encoded = encodeParams(map("openid", openid, "access_token", accessToken, "t", String.valueOf(t)));
            String resp; try { resp = httpPost(buildUrl(qqLoginCode), encoded[0]); } catch (IOException e) { return T3LoginResult.fail(e.getMessage()); }
            String decoded; try { decoded = decodeResponse(resp); } catch (Exception e) { return T3LoginResult.fail(e.getMessage()); }
            Map<String, Object> json; try { json = parseJson(decoded); } catch (Exception e) { return T3LoginResult.fail("响应不是有效的JSON格式"); }
            if (getCode(json) != 200) return T3LoginResult.fail(getStr(json,"msg") != null ? getStr(json,"msg") : "未知错误");
            T3LoginResult r = new T3LoginResult(); r.success = true;
            r.id = getStr(json,"id"); r.endTime = getStr(json,"end_time"); r.statecode = getStr(json,"statecode");
            r.recharge = getStr(json,"recharge"); r.useTime = getStr(json,"use_time");
            r.available = getStr(json,"available"); r.imei = getStr(json,"imei");
            r.change = getStr(json,"change"); r.core = getStr(json,"core");
            this.statecode = r.statecode; this.endTime = r.endTime;
            return r;
        } catch (Exception e) { return T3LoginResult.fail(e.getMessage()); }
    }
    public T3Result userHeartbeat(String user, String pass, String statecode) { return simpleRequest(userHeartbeatCode, "用户心跳", map("user", user, "pass", pass, "statecode", statecode)); }
    public T3Result bindQQ(String user, String pass, String openid, String accessToken) { return simpleRequest(bindQQCode, "绑定QQ", map("user", user, "pass", pass, "openid", openid, "access_token", accessToken)); }
    public T3Result changePassword(String user, String oldpass, String newpass) { return simpleRequest(changePasswordCode, "修改密码", map("user", user, "oldpass", oldpass, "newpass", newpass)); }
    public T3Result userCancel(String user, String pass) { return simpleRequest(userCancelCode, "用户注销", map("user", user, "pass", pass)); }
    public T3Result recharge(String user, String card) { return simpleRequest(rechargeCode, "用户充值", map("user", user, "card", card)); }

    // ============================================================
    // 设备与安全
    // ============================================================
    public T3Result unbindKami(String kami, String imei) { return simpleRequest(unbindCode, "解绑设备", map("kami", kami, "imei", imei)); }
    public T3Result unbindUser(String user, String pass, String imei) { return simpleRequest(unbindCode, "解绑设备", map("user", user, "pass", pass, "imei", imei)); }
    public T3Result ipUnbindKami(String kami) { return simpleRequest(ipUnbindCode, "IP解绑", map("kami", kami)); }
    public T3Result ipUnbindUser(String user, String pass) { return simpleRequest(ipUnbindCode, "IP解绑", map("user", user, "pass", pass)); }
    public T3Result disableKami(String kami) { return simpleRequest(disableCode, "禁用", map("kami", kami)); }
    public T3Result disableUser(String user, String pass) { return simpleRequest(disableCode, "禁用", map("user", user, "pass", pass)); }

    // ============================================================
    // 远程变量
    // ============================================================
    public T3VariableResult getVariableByKami(String kami, String valueid, String valuename) {
        T3Result r = simpleRequest(getVariableCode, "获取变量", map("kami", kami, "valueid", valueid, "valuename", valuename));
        return r.success ? T3VariableResult.ok(r.msg) : T3VariableResult.fail(r.error);
    }
    public T3VariableResult getVariableByUser(String user, String pass, String valueid, String valuename) {
        T3Result r = simpleRequest(getVariableCode, "获取变量", map("user", user, "pass", pass, "valueid", valueid, "valuename", valuename));
        return r.success ? T3VariableResult.ok(r.msg) : T3VariableResult.fail(r.error);
    }
    public T3Result modifyVariableByKami(String kami, String valueid, String valuecontent) { return simpleRequest(modifyVariableCode, "修改变量", map("kami", kami, "valueid", valueid, "valuecontent", valuecontent)); }
    public T3Result modifyVariableByUser(String user, String pass, String valueid, String valuecontent) { return simpleRequest(modifyVariableCode, "修改变量", map("user", user, "pass", pass, "valueid", valueid, "valuecontent", valuecontent)); }

    // ============================================================
    // 核心数据
    // ============================================================
    public T3Result modifyCoreByKami(String kami, String core) { return simpleRequest(modifyCoreCode, "修改核心数据", map("kami", kami, "core", core)); }
    public T3Result modifyCoreByUser(String user, String pass, String core) { return simpleRequest(modifyCoreCode, "修改核心数据", map("user", user, "pass", pass, "core", core)); }
    public T3CoreResult getCoreByKami(String kami) { T3Result r = simpleRequest(getKamiCoreCode, "获取卡密核心数据", map("kami", kami)); return r.success ? T3CoreResult.ok(r.msg) : T3CoreResult.fail(r.error); }
    public T3CoreResult getCoreByUser(String user, String pass) { T3Result r = simpleRequest(getUserCoreCode, "获取用户核心数据", map("user", user, "pass", pass)); return r.success ? T3CoreResult.ok(r.msg) : T3CoreResult.fail(r.error); }

    // ============================================================
    // 在线数量
    // ============================================================
    public T3OnlineResult getOnlineKamiCount() {
        T3Result r = simpleRequest(onlineKamiCode, "获取在线卡密数量", map());
        if (r.success) { try { return T3OnlineResult.ok(Integer.parseInt(r.msg)); } catch (Exception e) { return T3OnlineResult.ok(0); } }
        return T3OnlineResult.fail(r.error);
    }
    public T3OnlineResult getOnlineUserCount() {
        T3Result r = simpleRequest(onlineUserCode, "获取在线用户数量", map());
        if (r.success) { try { return T3OnlineResult.ok(Integer.parseInt(r.msg)); } catch (Exception e) { return T3OnlineResult.ok(0); } }
        return T3OnlineResult.fail(r.error);
    }
}