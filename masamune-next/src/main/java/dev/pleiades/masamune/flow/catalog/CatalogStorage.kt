package dev.pleiades.masamune.flow.catalog

import dev.pleiades.masamune.flow.model.BlockCategory
import dev.pleiades.masamune.flow.model.BlockSpec

/**
 * Local files, FTP, the two cloud drives Automate speaks, and zip archives.
 *
 * Nothing here is gated. That is not an oversight: file access on current Android is decided
 * by the Storage Access Framework and by which tree the user has granted, neither of which is
 * a [Requirement] this catalog can test before placing a block. The honest gate for storage
 * lives at the point of use, not in the palette.
 *
 * Blocks are listed in Automate's own palette order, which is the order this catalog and the
 * palette both render. See `docs/donors/RE-automate.md`.
 */
internal val STORAGE_BLOCKS: List<BlockSpec> = category(BlockCategory.STORAGE) {
    ftpAndLocalFiles()
    cloudDrivesVolumesAndArchives()
}

/** FTP transfer, and the local filesystem including monitoring and extraction. */
private fun Blocks.ftpAndLocalFiles() {
    action(
        "ftp_delete", "FTP delete",
        "Deletes content on an FTP server.",
        args = listOf(
            text("host", "Host or IP address"),
            num("port", "Port", "21 or 990 for FTPS"),
            text("account", "Log in account", "anonymous"),
            any("charset", "Control encoding", "UTF-8"),
            flag("security", "Connection security"),
            flag("trust", "Certificate"),
            any("prot", "Data channel protection", "Clear"),
            text("remotePath", "Remote path"),
            flag("recursive", "Recursive"),
        ),
    )
    action(
        "ftp_download", "FTP download",
        "Downloads content from an FTP server.",
        args = listOf(
            text("host", "Host or IP address"),
            num("port", "Port", "21 or 990 for FTPS"),
            text("account", "Log in account", "anonymous"),
            any("charset", "Control encoding", "UTF-8"),
            flag("security", "Connection security"),
            flag("trust", "Certificate"),
            any("prot", "Data channel protection", "Clear"),
            text("remotePath", "Remote path"),
            text("localPath", "Local path"),
            flag("recursive", "Recursive"),
        ),
    )
    action(
        "ftp_list", "FTP list",
        "Lists the content on an FTP server.",
        args = listOf(
            text("host", "Host or IP address"),
            num("port", "Port", "21 or 990 for FTPS"),
            text("account", "Log in account", "anonymous"),
            any("charset", "Control encoding", "UTF-8"),
            flag("security", "Connection security"),
            flag("trust", "Certificate"),
            any("prot", "Data channel protection", "Clear"),
            text("remotePath", "Remote path"),
            any("modifiedSince", "Modified since"),
            any("types", "Content type", "all content"),
        ),
        outputs = listOf(
            out("varFiles", "Filenames"),
        ),
    )
    action(
        "ftp_make_directory", "FTP make directory",
        "Creates a directory on an FTP server.",
        args = listOf(
            text("host", "Host or IP address"),
            num("port", "Port", "21 or 990 for FTPS"),
            text("account", "Log in account", "anonymous"),
            any("charset", "Control encoding", "UTF-8"),
            flag("security", "Connection security"),
            flag("trust", "Certificate"),
            any("prot", "Data channel protection", "Clear"),
            text("remotePath", "Remote path"),
        ),
    )
    action(
        "ftp_upload", "FTP upload",
        "Uploads content to an FTP server.",
        args = listOf(
            text("host", "Host or IP address"),
            num("port", "Port", "21 or 990 for FTPS"),
            text("account", "Log in account", "anonymous"),
            any("charset", "Control encoding", "UTF-8"),
            flag("security", "Connection security"),
            flag("trust", "Certificate"),
            any("prot", "Data channel protection", "Clear"),
            text("localPath", "Local path"),
            text("remotePath", "Remote path"),
            flag("recursive", "Recursive"),
        ),
    )
    action(
        "file_apk_extract", "File APK extract",
        "Extracts content from an Android Package (APK) file.",
        args = listOf(
            text("sourceFile", "File"),
        ),
        outputs = listOf(
            out("varManifest", "Manifest"),
        ),
    )
    action(
        "file_copy", "File copy",
        "Copies content on external storage.",
        args = listOf(
            text("sourcePath", "Source path"),
            text("targetPath", "Destination path"),
            flag("recursive", "Recursive"),
            flag("onlyNewerFiles", "Update"),
        ),
    )
    action(
        "file_delete", "File delete",
        "Deletes content on external storage.",
        args = listOf(
            text("path", "Path"),
            flag("recursive", "Recursive"),
        ),
    )
    decision(
        "file_exists", "File exists",
        "Checks if a file or directory exists on external storage.",
        proceed = WATCH,
        args = listOf(
            text("path", "Path"),
        ),
        outputs = listOf(
            out("varType", "Type"),
            out("varSize", "Size"),
            out("varLastModified", "Last modified"),
        ),
    )
    action(
        "file_list", "File list",
        "Lists content on external storage.",
        args = listOf(
            text("path", "Path"),
            any("modifiedSince", "Modified since"),
            any("types", "Content type", "all content"),
            flag("recursive", "Recursive"),
        ),
        outputs = listOf(
            out("varFiles", "Filenames"),
        ),
    )
    action(
        "file_make_directory", "File make directory",
        "Creates a directory on external storage.",
        args = listOf(
            text("path", "Path"),
        ),
    )
    action(
        "file_monitor", "File monitor",
        "Awaits alterations to the file system.",
        args = listOf(
            text("path", "Path"),
            any("events", "Events"),
        ),
        outputs = listOf(
            out("varAlterationPath", "Alteration path"),
            out("varAlterationEvent", "Alteration event"),
        ),
    )
    action(
        "file_move", "File move",
        "Moves content on the external storage.",
        args = listOf(
            text("sourcePath", "Source path"),
            text("targetPath", "Destination path"),
            flag("recursive", "Recursive"),
        ),
    )
    decision(
        "file_multipart_extract", "File multipart extract",
        "Extracts one part from a multipart-encoded file.",
        args = listOf(
            text("sourceFile", "File"),
            any("boundaryMark", "Boundary mark"),
            text("partName", "Part name", "any"),
            any("partIndex", "Part index", "0 (zero)"),
            any("saveBody", "Save part", "Don't save"),
            any("bodyPath", "Part content path", "a file in the \"Download\" directory"),
        ),
        outputs = listOf(
            out("varPartBody", "Part content"),
            out("varPartHeaders", "Part headers"),
        ),
    )
    decision(
        "file_pick", "File pick",
        "Lets the user choose a file system path.",
        args = listOf(
            any("types", "Content type", "any content"),
            flag("writable", "Writable", "no"),
            flag("allowNew", "Allow new", "no"),
            any("fileExtension", "File extension", "all files"),
            any("initialPath", "Initial path", "primary external storage"),
            num("timeout", "Timeout", "no timeout"),
            any("notificationChannelId", "Notification channel", "the flow default or Flow"),
            flag("startActivity", "Show window"),
        ),
        outputs = listOf(
            out("varPickedPath", "Picked path"),
        ),
    )
    action(
        "file_read", "File read",
        "Loads the content of a text file.",
        args = listOf(
            text("path", "File"),
            any("charset", "Charset", "automatic detection"),
        ),
        outputs = listOf(
            out("varContent", "Text content"),
        ),
    )
    action(
        "file_write", "File write",
        "Writes or append content to a file.",
        args = listOf(
            any("content", "Content"),
            text("path", "File"),
            any("charset", "Charset", "UTF-8"),
            any("decode", "Decode", "no decoding, i"),
            flag("append", "Append"),
        ),
    )
}

/** Google Drive, OneDrive, storage volumes and zip archives. */
private fun Blocks.cloudDrivesVolumesAndArchives() {
    action(
        "gdrive_delete", "Google Drive delete",
        "Deletes content on Google Drive.",
        args = listOf(
            text("account", "Google account"),
            text("remotePath", "Remote path"),
            flag("recursive", "Recursive"),
            flag("trash", "Trash", "no"),
        ),
    )
    action(
        "gdrive_download", "Google Drive download",
        "Downloads content from Google Drive.",
        args = listOf(
            text("account", "Google account"),
            text("remotePath", "Remote path"),
            text("localPath", "Local path"),
            flag("recursive", "Recursive"),
            flag("onlyNewerFiles", "Update"),
        ),
    )
    decision(
        "gdrive_file_exists", "Google Drive file exists",
        "Checks if a file or directory exists on Google Drive.",
        args = listOf(
            text("account", "Google account"),
            text("remotePath", "Remote path"),
        ),
        outputs = listOf(
            out("varType", "Type"),
            out("varSize", "Size"),
            out("varLastModified", "Last modified"),
        ),
    )
    action(
        "gdrive_list", "Google Drive list",
        "Lists the content on Google Drive.",
        args = listOf(
            text("account", "Google account"),
            text("remotePath", "Remote path"),
            any("modifiedSince", "Modified since"),
            any("types", "Content type", "all content"),
            flag("recursive", "Recursive"),
        ),
        outputs = listOf(
            out("varFiles", "Filenames"),
        ),
    )
    action(
        "gdrive_make_directory", "Google Drive make directory",
        "Creates a directory on Google Drive.",
        args = listOf(
            text("account", "Google account"),
            text("remotePath", "Remote path"),
        ),
    )
    action(
        "gdrive_share", "Google Drive share",
        "Enables public sharing of an file on Google Drive.",
        args = listOf(
            text("account", "Google account"),
            text("remotePath", "Remote path"),
        ),
        outputs = listOf(
            out("varDownloadUrl", "Download URL"),
        ),
    )
    action(
        "gdrive_upload", "Google Drive upload",
        "Uploads content to Google Drive.",
        args = listOf(
            text("account", "Google account"),
            text("localPath", "Local path"),
            text("remotePath", "Remote path"),
            flag("recursive", "Recursive"),
            flag("onlyNewerFiles", "Update"),
        ),
    )
    action(
        "onedrive_delete", "OneDrive delete",
        "Deletes content on Microsoft OneDrive.",
        args = listOf(
            text("account", "Microsoft account"),
            text("remotePath", "Remote path"),
            flag("recursive", "Recursive"),
            flag("trash", "Trash", "yes"),
        ),
    )
    action(
        "onedrive_download", "OneDrive download",
        "Downloads content from Microsoft OneDrive.",
        args = listOf(
            text("account", "Microsoft account"),
            text("remotePath", "Remote path"),
            text("localPath", "Local path"),
            flag("recursive", "Recursive"),
            flag("onlyNewerFiles", "Update"),
        ),
    )
    decision(
        "onedrive_file_exists", "OneDrive file exists",
        "Checks if a file or directory exists on Microsoft OneDrive.",
        args = listOf(
            text("account", "Microsoft account"),
            text("remotePath", "Remote path"),
        ),
        outputs = listOf(
            out("varType", "Type"),
            out("varSize", "Size"),
            out("varLastModified", "Last modified"),
        ),
    )
    action(
        "onedrive_list", "OneDrive list",
        "Lists the content on Microsoft OneDrive.",
        args = listOf(
            text("account", "Microsoft account"),
            text("remotePath", "Remote path"),
            any("modifiedSince", "Modified since"),
            any("types", "Content type", "all content"),
            flag("recursive", "Recursive"),
        ),
        outputs = listOf(
            out("varFiles", "Filenames"),
        ),
    )
    action(
        "onedrive_make_directory", "OneDrive make directory",
        "Creates a directory on Microsoft OneDrive.",
        args = listOf(
            text("account", "Microsoft account"),
            text("remotePath", "Remote path"),
        ),
    )
    action(
        "onedrive_upload", "OneDrive upload",
        "Uploads content to Microsoft OneDrive.",
        args = listOf(
            text("account", "Microsoft account"),
            text("localPath", "Local path"),
            text("remotePath", "Remote path"),
            flag("recursive", "Recursive"),
            flag("onlyNewerFiles", "Update"),
        ),
    )
    action(
        "storage_volume_list", "Storage media list",
        "Lists mounted storage media/volumes.",
        args = listOf(
            flag("writable", "Writable", "no"),
        ),
        outputs = listOf(
            out("varMountPaths", "Mounting point paths"),
        ),
    )
    decision(
        "storage_media_mounted", "Storage media mounted",
        "Checks if the storage volume at mounting point path (e.g. an SD card or USB drive) " +
            "is mounted (available) or unmounted (unavailable).",
        proceed = WATCH,
        args = listOf(
            text("path", "Mounting point path", "to primary external storage"),
            flag("writable", "Writable"),
        ),
        outputs = listOf(
            out("varMountPath", "Mounting point path"),
        ),
    )
    decision(
        "storage_space", "Storage space",
        "Checks if the free storage space is more that 10% (okay) or less (low).",
        proceed = WATCH,
        outputs = listOf(
            out("varUsableSpace", "Usable space"),
        ),
    )
    action(
        "zip_compress", "Zip compress",
        "Compresses content into a zip file.",
        args = listOf(
            text("zipFile", "Destination zip file"),
            text("sourcePath", "Source path"),
            any("targetPath", "Destination folder in zip", "zip root directory"),
            flag("recursive", "Recursive", "false"),
            flag("update", "Update", "false"),
            any("compressionMethod", "Compression method", "Deflated normal"),
        ),
    )
    action(
        "zip_extract", "Zip extract",
        "Extracts content from a zip file.",
        args = listOf(
            text("zipFile", "Source zip file"),
            text("sourcePath", "Source path in zip", "zip root directory"),
            text("targetPath", "Destination path"),
            flag("recursive", "Recursive", "true"),
        ),
    )
    action(
        "zip_list", "Zip list",
        "Lists content of a zip file.",
        args = listOf(
            text("zipFile", "Source zip file"),
            text("sourcePath", "Source path in zip", "zip root directory"),
            any("modifiedSince", "Modified since"),
            any("types", "Content type", "all content"),
            flag("recursive", "Recursive"),
        ),
        outputs = listOf(
            out("varFiles", "Filenames"),
        ),
    )
}
