package com.baidu.disconf.core.common.restful.impl;

import java.io.File;
import java.net.URL;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.glassfish.jersey.jackson.JacksonFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.baidu.disconf.core.common.restful.RestfulMgr;
import com.baidu.disconf.core.common.restful.core.RemoteUrl;
import com.baidu.disconf.core.common.restful.core.UnreliableInterface;
import com.baidu.disconf.core.common.restful.retry.RetryStrategy;
import com.baidu.disconf.core.common.restful.type.FetchConfFile;
import com.baidu.disconf.core.common.restful.type.RestfulGet;
import com.baidu.disconf.core.common.utils.MyStringUtils;
import com.github.knightliao.apollo.utils.config.ConfigLoaderUtils;
import com.github.knightliao.apollo.utils.io.OsUtil;

/**
 * RestFul的一个实现, 独立模块
 *
 * @author liaoqiqi
 * @version 2014-6-10
 */
public class RestfulMgrImpl implements RestfulMgr {

    protected static final Logger LOGGER = LoggerFactory.getLogger(RestfulMgrImpl.class);

    /**
     * 连接器
     */
    private Client client = null;

    /**
     * 重试策略
     */
    private RetryStrategy retryStrategy;

    public RestfulMgrImpl(RetryStrategy retryStrategy) {

        this.retryStrategy = retryStrategy;
    }

    /**
     * @return void
     *
     * @throws Exception
     * @Description: 初始化
     * @author liaoqiqi
     * @date 2013-6-16
     */
    public void init() throws Exception {

        client = ClientBuilder.newBuilder().register(JacksonFeature.class).build();

        if (client == null) {
            throw new Exception("RestfulMgr init failed!");
        }
    }

    /**
     * 获取JSON数据
     *
     * @param clazz
     * @param remoteUrl
     *
     * @return
     *
     * @throws Exception
     */
    public <T> T getJsonData(Class<T> clazz, RemoteUrl remoteUrl, int retryTimes, int retyrSleepSeconds)
        throws Exception {

        for (URL url : remoteUrl.getUrls()) {

            WebTarget webtarget = client.target(url.toURI());

            LOGGER.debug("start to query url : " + webtarget.getUri().toString());

            Invocation.Builder builder = webtarget.request(MediaType.APPLICATION_JSON_TYPE);

            // 可重试的下载
            UnreliableInterface unreliableImpl = new RestfulGet(builder);

            try {

                Response response = (Response) retryStrategy.retry(unreliableImpl, retryTimes, retyrSleepSeconds);

                return response.readEntity(clazz);

            } catch (Exception e) {

                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e1) {
                    LOGGER.info("pass");
                }
            }
        }

        throw new Exception("cannot get: " + remoteUrl);
    }

    /**
     * @return void
     *
     * @Description：关闭
     * @author liaoqiqi
     * @date 2013-6-16
     */
    public void close() {

        if (client != null) {
            client.close();
        }
    }

    /**
     * @param remoteUrl            远程地址
     * @param fileName             文件名
     * @param localFileDir         本地文件地址
     * @param isTransfer2Classpath 是否将下载的文件放到Classpath目录下
     *
     * @return 如果是放到Classpath目录下，则返回相对Classpath的路径，如果不是，则返回全路径
     *
     * @throws Exception
     */
    public String downloadFromServer(RemoteUrl remoteUrl, String fileName, String localFileDir,
                                     boolean isTransfer2Classpath, int retryTimes, int retrySleepSeconds)
        throws Exception {

        // 临时下载目录
        String tmpFileDir = "./disconf/download";

        // 待下载的路径
        String localFilePath = OsUtil.pathJoin(localFileDir, fileName);
        String tmpFilePath = OsUtil.pathJoin(tmpFileDir, fileName);

        // 唯一标识化本地文件
        String tmpFilePathUnique = MyStringUtils.getRandomName(tmpFilePath);

        // 相应的File对象
        File localFile = new File(localFilePath);
        File tmpFilePathUniqueFile = new File(tmpFilePathUnique);

        try {

            // 可重试的下载
            retry4ConfDownload(remoteUrl, tmpFilePathUniqueFile, retryTimes, retrySleepSeconds);

            // 将 tmp file 移到 localFileDir
            OsUtil.transferFileAtom(tmpFilePathUniqueFile, localFile, false);

            // 再次转移至classpath目录下
            if (isTransfer2Classpath) {

                File classpathFile = getLocalDownloadFileInClasspath(fileName);
                if (classpathFile != null) {

                    // 从下载文件复制到classpath 原子性的做转移
                    OsUtil.transferFileAtom(tmpFilePathUniqueFile, classpathFile, true);
                    localFile = classpathFile;

                } else {
                    LOGGER.warn("classpath is null, cannot transfer " + fileName + " to classpath");
                }
            }

            LOGGER.debug("Move to: " + localFile.getAbsolutePath());

        } catch (Exception e) {
            LOGGER.warn("download file failed, using previous download file.", e);
        }

        //
        // 下载失败
        //
        if (!localFile.exists()) {
            throw new Exception("target file cannot be found! " + fileName);
        }

        //
        // 下面为下载成功
        //

        // 如果是使用CLASS路径的，则返回相对classpath的路径
        if (!ConfigLoaderUtils.CLASS_PATH.isEmpty()) {
            String relativePathString = OsUtil.getRelativePath(localFile, new File(ConfigLoaderUtils.CLASS_PATH));
            if (relativePathString != null) {
                if (new File(relativePathString).isFile()) {
                    return relativePathString;
                }
            }
        }

        // 否则, 返回全路径
        return localFile.getAbsolutePath();
    }

    /**
     * Retry封装 RemoteUrl 支持多Server的下载
     *
     * @param remoteUrl
     * @param localTmpFile
     * @param retryTimes
     * @param sleepSeconds
     *
     * @return
     */
    private Object retry4ConfDownload(RemoteUrl remoteUrl, File localTmpFile, int retryTimes, int sleepSeconds)
        throws Exception {

        for (URL url : remoteUrl.getUrls()) {

            // 可重试的下载
            UnreliableInterface unreliableImpl = new FetchConfFile(url, localTmpFile);

            try {

                return retryStrategy.retry(unreliableImpl, retryTimes, sleepSeconds);

            } catch (Exception e) {

                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e1) {
                    LOGGER.info("pass");
                }
            }
        }

        throw new Exception("download failed.");
    }

    /**
     * @param fileName
     *
     * @return File
     *
     * @throws Exception
     * @Description: 获取在CLASSPATH下的文件，如果找不到CLASSPATH，则返回null
     * @author liaoqiqi
     * @date 2013-6-20
     */
    private static File getLocalDownloadFileInClasspath(String fileName) throws Exception {

        String classpath = ConfigLoaderUtils.CLASS_PATH;

        if (classpath == null) {
            return null;
        }
        return new File(OsUtil.pathJoin(classpath, fileName));
    }

}
