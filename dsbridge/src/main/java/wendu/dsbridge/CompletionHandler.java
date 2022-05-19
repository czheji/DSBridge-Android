package wendu.dsbridge;

/**
 * Created by du on 16/12/31.
 */

public interface  CompletionHandler<T> {
    /**
     * 异步完成
     * @param retValue 返回值
     */
    void complete(T retValue);

    /**
     * 异步完成，无返回值
     */
    void complete();

    /**
     * 多次回调
     * @param value 返回值
     */
    void setProgressData(T value);

    /**
     * 异步异常
     * @param throwable 异常
     */
    void error(Throwable throwable);
}
