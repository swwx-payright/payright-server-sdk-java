package com.payright.model;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.payright.config.PayrightConfig;
import com.payright.config.SignConfig;
import com.payright.exception.AuthorizationException;
import com.payright.exception.InvalidRequestException;
import com.payright.sign.RSA;
import org.apache.commons.lang.StringUtils;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.LayeredConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLContexts;
import org.apache.http.conn.ssl.TrustStrategy;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.util.EntityUtils;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.nio.charset.Charset;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.*;

/**
 * Created by xiaowei.wang on 2016/4/26.
 */
public abstract class Payright extends PayrightBase {

    private static CloseableHttpClient httpsClient = null;

    static class AnyTrustStrategy implements TrustStrategy {

        @Override
        public boolean isTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            return true;
        }

    }

    static {
        try {
            RegistryBuilder<ConnectionSocketFactory> registryBuilder = RegistryBuilder.create();
            ConnectionSocketFactory plainSF = new PlainConnectionSocketFactory();
            registryBuilder.register("http", plainSF);

            KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            SSLContext sslContext =
                    SSLContexts.custom().useTLS().loadTrustMaterial(trustStore, new AnyTrustStrategy()).build();
            LayeredConnectionSocketFactory sslSF =
                    new SSLConnectionSocketFactory(sslContext, SSLConnectionSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER);

            registryBuilder.register("https", sslSF);

            Registry<ConnectionSocketFactory> registry = registryBuilder.build();

            PoolingHttpClientConnectionManager connManager = new PoolingHttpClientConnectionManager(registry);
            connManager.setMaxTotal(500);

            httpsClient = HttpClientBuilder.create().setConnectionManager(connManager).build();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     *
     * @param url
     * @param reqData
     * @param clazz
     * @param <T>
     * @return
     * @throws AuthorizationException
     * @throws IOException
     * @throws InvalidRequestException
     */
    protected static <T> T request(String url, String reqData, Class<T> clazz) throws AuthorizationException, IOException, InvalidRequestException {
        if(StringUtils.isBlank(SignConfig.SECRET_KEY)){
            throw new AuthorizationException("Secret key can not be blank.Please set your Secret key in com.payright.config.SignConfig");
        }

        if(StringUtils.isBlank(SignConfig.PRIVATE_KEY)){
            throw new AuthorizationException("RSA Private key can not be blank.Please set your RSA Private key  in com.payright.config.SignConfig");
        }

        if(StringUtils.isBlank(SignConfig.PAYRIGHT_PUBLIC_KEY)){
            throw new InvalidRequestException("Payright Public key can not be blank.Please set your Payright Public key in com.payright.config.SignConfig");
        }
        Map<String, String> result = null;
        if (StringUtils.isBlank(reqData)){
            result = buildGetRequest(url);
        }else {
            result = buildPostRequest(url, reqData);
        }

        return dealWithResult(result,clazz);

    }

    /**
     * 处理返回数据
     * @param result
     * @param clazz
     * @param <T>
     * @return
     */
    private static <T> T dealWithResult(Map<String, String> result,Class<T> clazz) {
        int resultCode = Integer.valueOf(result.get("code")).intValue();
        String resultData = result.get("data");
        if (resultCode >= 400){
            return JSON.parseObject(resultData,clazz);
        }

        Map<String,String> map = (Map<String, String>) JSONObject.parse(resultData);
        boolean flag = RSA.verify(map.get(PayrightConfig.RESDATA),map.get(PayrightConfig.SIGN), SignConfig.PAYRIGHT_PUBLIC_KEY);
        if(!flag){
            return JSON.parseObject(null,clazz);
        }

        return JSON.parseObject(map.get(PayrightConfig.RESDATA),clazz);
    }

    /**
     * POST请求
     * @param url
     * @param reqData
     * @return
     * @throws IOException
     */
    private static  Map<String, String> buildPostRequest(String url, String reqData) throws IOException {
        Map<String, String> result = new HashMap<String, String>();

        HttpPost httpPost = new HttpPost(url);

        httpPost.setEntity(
                new ByteArrayEntity(reqData.getBytes(Charset.forName(PayrightConfig.CHARSET)), ContentType.APPLICATION_JSON));

        setCustomHeaders(httpPost);

        //签名
        String sign = RSA.sign(reqData, SignConfig.PRIVATE_KEY);
        httpPost.addHeader(PayrightConfig.SIGN,sign);

        CloseableHttpResponse response = httpsClient.execute(httpPost);
        try {
            HttpEntity entity = response.getEntity();
            if (entity != null) {
                try {
                    String data = EntityUtils.toString(entity, Charset.forName(PayrightConfig.CHARSET));
                    result.put("code", String.valueOf(response.getStatusLine().getStatusCode()));
                    result.put("data", data);
                } finally {
                    EntityUtils.consumeQuietly(entity);
                }
            }
        } finally {
            response.close();
            httpPost.releaseConnection();
        }

        return result;

    }

    /**
     * GET请求
     * @param url
     * @return
     * @throws IOException
     */
    public static Map<String, String> buildGetRequest(String url) throws IOException {
        Map<String, String> result = new HashMap<String, String>();

        HttpGet httpGet = new HttpGet(url);

        setCustomHeaders(httpGet);

        CloseableHttpResponse response = httpsClient.execute(httpGet);
        try {
            HttpEntity entity = response.getEntity();
            if (entity != null) {
                try {
                    result.put("code", String.valueOf(response.getStatusLine().getStatusCode()));
                    result.put("data", EntityUtils.toString(entity, Charset.forName(PayrightConfig.CHARSET)));
                } finally {
                    EntityUtils.consumeQuietly(entity);
                }
            }
        } finally {
            response.close();
            httpGet.releaseConnection();
        }

        return result;
    }

    /**
     * Request Header
     * @param request
     */
    private static void setCustomHeaders(HttpRequestBase request) {
        request.addHeader("Content-Type", "application/json; charset=" + PayrightConfig.CHARSET);
        request.addHeader("Authorization",SignConfig.SECRET_KEY);

        String[] propertyNames = {"os.name", "os.version", "os.arch",
                "java.version", "java.vendor", "java.vm.version",
                "java.vm.vendor"};
        Map<String, String> propertyMap = new HashMap<String, String>();
        for (String propertyName : propertyNames) {
            propertyMap.put(propertyName, System.getProperty(propertyName));
        }
        propertyMap.put("lang", "Java");
        propertyMap.put("publisher", "Payright");
        request.addHeader("X-Payright-Client-User-Agent", JSON.toJSONString(propertyMap));
    }

}
