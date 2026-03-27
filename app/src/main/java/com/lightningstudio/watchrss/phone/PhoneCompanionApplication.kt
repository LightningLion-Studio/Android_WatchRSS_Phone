package com.lightningstudio.watchrss.phone

import android.app.Application

class PhoneCompanionApplication : Application() {
    val container: PhoneCompanionContainer by lazy {
        PhoneCompanionContainer(this)
    }
}
