# 生产包保底规则。实测零规则也能跑（Hilt/Room/Retrofit/OkHttp/DataStore/序列化
# 各库自带的 consumer rules 已 cover），这里只防“以后加新 Model/新库”翻车。
# 体积代价：几乎为零（只 keep 住自家数据类）。

-keepattributes Signature, InnerClasses, EnclosingMethod

# kotlinx-serialization：自家 @Serializable 模型及生成的 serializer 方法
-keep @kotlinx.serialization.Serializable class cn.debubu.tingbili.** { *; }
-keepclasseswithmembers class cn.debubu.tingbili.** {
    kotlinx.serialization.KSerializer serializer(...);
}
