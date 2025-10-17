setup you must choose a random NTFY.SH topic and have your voip.ms did make a GET request to the topic when a new sms is received

this is easily done in the DID Settings on voip.ms right in the area where you enable sms for a specific DID.

https://ntfy.sh/mywebhook/publish?message=update

in this example "mywebhook" is the topic you should choose something more random and more unuiqe

the app will watch the topic you configure in settings and any time "update" is seen on the topic thats configured both on voip.ms and on the app that are matching your app will refresh and update the sms

ntfy.sh never sees any of your messages, contacts, dids the only thing they see is a GET request coming from a server owned by voip.ms containing "update"

build instructions

./gradlew assembleFdroidFullRelease

to build you must have jdk, gradle, python & android sdk 35