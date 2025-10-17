setup you must choose a random NTFY.SH topic and have your voip.ms did make a GET request to the topic when a new sms is received

```https://ntfy.sh/mywebhook/publish?message=update```

the app will watch the topic and any time "update" is seen on the topic configured on voip.ms and on the app that are matching your app will refresh and update the sms


```./gradlew assembleFdroidFullRelease```

to build you must have jdk, gradle & android sdk 35
