package com.lightningstudio.watchrss.phone.data.local

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.FileSystemException
import java.nio.file.StandardOpenOption

internal fun syncDirectory(directory: File) {
    if (System.getProperty("java.vm.name") != "Dalvik") {
        FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
        return
    }

    // Android's Java file channel is for regular files. Open a directory descriptor explicitly.
    try {
        val fd = Os.open(directory.path, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC, 0)
        try {
            if (!OsConstants.S_ISDIR(Os.fstat(fd).st_mode)) throw java.nio.file.NotDirectoryException(directory.path)
            Os.fsync(fd)
        } finally {
            Os.close(fd)
        }
    } catch (failure: ErrnoException) {
        throw FileSystemException(directory.path, null, "directory fsync failed").apply { initCause(failure) }
    }
}
