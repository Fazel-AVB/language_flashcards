# kotlinx.serialization: keep the generated serializers of the saved session.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.fazelavb.flashcards.** {
    *** Companion;
}
-keepclasseswithmembers class com.fazelavb.flashcards.** {
    kotlinx.serialization.KSerializer serializer(...);
}
