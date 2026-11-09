package com.example.froottaa

interface Ui {
    fun status(msg: String)
    fun progress(done: Long, total: Long)
}
