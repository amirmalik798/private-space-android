package com.privatespace.android.data

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultDirectoryListingTest {
    @Test
    fun `lists children in a readable directory`() {
        val directory = Files.createTempDirectory("vault-listing").toFile()
        try {
            val child = File(directory, "entry.tmp")
            assertTrue(child.createNewFile())

            val result = VaultDirectoryListing.listChildren(directory, "test cache")

            assertEquals(listOf("entry.tmp"), result.map { it.name })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `treats an empty directory as a valid empty listing`() {
        val directory = Files.createTempDirectory("vault-listing-empty").toFile()
        try {
            assertEquals(0, VaultDirectoryListing.listChildren(directory, "test cache").size)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `fails closed when the directory is missing`() {
        val parent = Files.createTempDirectory("vault-listing-missing").toFile()
        val missing = File(parent, "missing")
        try {
            try {
                VaultDirectoryListing.listChildren(missing, "test cache")
                throw AssertionError("A missing directory must not be treated as empty.")
            } catch (_: IOException) {
                // Expected: cleanup/recovery must not continue with an unknown listing.
            }
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun `fails closed when the path is a regular file`() {
        val parent = Files.createTempDirectory("vault-listing-file").toFile()
        val file = File(parent, "not-a-directory")
        try {
            assertTrue(file.createNewFile())
            try {
                VaultDirectoryListing.listChildren(file, "test cache")
                throw AssertionError("A regular file must not be treated as an empty directory.")
            } catch (_: IOException) {
                // Expected.
            }
        } finally {
            parent.deleteRecursively()
        }
    }
}
