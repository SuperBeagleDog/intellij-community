/*
 * Copyright 2000-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jetbrains.builtInWebServer

import com.google.common.cache.CacheBuilder
import com.google.common.net.InetAddresses
import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.application.ApplicationNamesInfo
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.catchAndLog
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.SystemInfoRt
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.io.FileUtilRt
import com.intellij.openapi.util.io.endsWithName
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.directoryStreamIfExists
import com.intellij.util.io.URLUtil
import com.intellij.util.isDirectory
import com.intellij.util.net.NetUtils
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.*
import io.netty.handler.codec.http.cookie.ClientCookieEncoder
import io.netty.handler.codec.http.cookie.DefaultCookie
import io.netty.handler.codec.http.cookie.ServerCookieDecoder
import org.jetbrains.ide.HttpRequestHandler
import org.jetbrains.io.*
import java.awt.datatransfer.StringSelection
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.nio.file.Path
import java.util.*
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

internal val LOG = Logger.getInstance(BuiltInWebServer::class.java)

class BuiltInWebServer : HttpRequestHandler() {
  override fun isAccessible(request: HttpRequest) = request.isLocalOrigin(onlyAnyOrLoopback = false, hostsOnly = true)

  override fun isSupported(request: FullHttpRequest) = super.isSupported(request) || request.method() == HttpMethod.POST

  override fun process(urlDecoder: QueryStringDecoder, request: FullHttpRequest, context: ChannelHandlerContext): Boolean {
    var host = request.host
    if (host.isNullOrEmpty()) {
      return false
    }

    val portIndex = host!!.indexOf(':')
    if (portIndex > 0) {
      host = host.substring(0, portIndex)
    }

    val projectName: String?
    val isIpv6 = host[0] == '[' && host.length > 2 && host[host.length - 1] == ']'
    if (isIpv6) {
      host = host.substring(1, host.length - 1)
    }

    if (isIpv6 || InetAddresses.isInetAddress(host) || isOwnHostName(host) || host.endsWith(".ngrok.io")) {
      if (urlDecoder.path().length < 2) {
        return false
      }
      projectName = null
    }
    else {
      projectName = host
    }
    return doProcess(urlDecoder, request, context, projectName)
  }
}

const val TOKEN_PARAM_NAME = "__ij-st"

private val STANDARD_COOKIE by lazy {
  val productName = ApplicationNamesInfo.getInstance().lowercaseProductName
  val configPath = PathManager.getConfigPath()
  val cookieName = productName + "-" + Integer.toHexString(configPath.hashCode())
  val file = File(configPath, cookieName)
  var token: String? = null
  if (file.exists()) {
    try {
      token = UUID.fromString(FileUtil.loadFile(file)).toString()
    }
    catch (e: Exception) {
      LOG.warn(e)
    }
  }
  if (token == null) {
    token = UUID.randomUUID().toString()
    FileUtil.writeToFile(file, token!!)
  }

  val cookie = DefaultCookie(cookieName, token!!)
  cookie.isHttpOnly = true
  cookie.setMaxAge(TimeUnit.DAYS.toMillis(365 * 10))
  cookie.setPath("/")
  cookie
}

// expire after access because we reuse tokens
private val tokens = CacheBuilder.newBuilder().expireAfterAccess(1, TimeUnit.MINUTES).build<String, Boolean>()

internal fun acquireToken(): String {
  var token = tokens.asMap().keys.firstOrNull()
  if (token == null) {
    token = UUID.randomUUID().toString()
    tokens.put(token, java.lang.Boolean.TRUE)
  }
  return token
}

private fun doProcess(urlDecoder: QueryStringDecoder, request: FullHttpRequest, context: ChannelHandlerContext, projectNameAsHost: String?): Boolean {
  val decodedPath = URLUtil.unescapePercentSequences(urlDecoder.path())
  var offset: Int
  var isEmptyPath: Boolean
  val isCustomHost = projectNameAsHost != null
  var projectName: String
  if (isCustomHost) {
    projectName = projectNameAsHost!!
    // host mapped to us
    offset = 0
    isEmptyPath = decodedPath.isEmpty()
  }
  else {
    offset = decodedPath.indexOf('/', 1)
    projectName = decodedPath.substring(1, if (offset == -1) decodedPath.length else offset)
    isEmptyPath = offset == -1
  }

  var candidateByDirectoryName: Project? = null
  val project = ProjectManager.getInstance().openProjects.firstOrNull(fun(project: Project): Boolean {
    if (project.isDisposed) {
      return false
    }

    val name = project.name
    if (isCustomHost) {
      // domain name is case-insensitive
      if (projectName.equals(name, ignoreCase = true)) {
        if (!SystemInfoRt.isFileSystemCaseSensitive) {
          // may be passed path is not correct
          projectName = name
        }
        return true
      }
    }
    else {
      // WEB-17839 Internal web server reports 404 when serving files from project with slashes in name
      if (decodedPath.regionMatches(1, name, 0, name.length, !SystemInfoRt.isFileSystemCaseSensitive)) {
        val isEmptyPathCandidate = decodedPath.length == (name.length + 1)
        if (isEmptyPathCandidate || decodedPath[name.length + 1] == '/') {
          projectName = name
          offset = name.length + 1
          isEmptyPath = isEmptyPathCandidate
          return true
        }
      }
    }

    if (candidateByDirectoryName == null && compareNameAndProjectBasePath(projectName, project)) {
      candidateByDirectoryName = project
    }
    return false
  }) ?: candidateByDirectoryName ?: return false

  if (isEmptyPath) {
    // we must redirect "jsdebug" to "jsdebug/" as nginx does, otherwise browser will treat it as a file instead of a directory, so, relative path will not work
    redirectToDirectory(request, context.channel(), projectName)
    return true
  }

  val path = toIdeaPath(decodedPath, offset)
  if (path == null) {
    HttpResponseStatus.BAD_REQUEST.orInSafeMode(HttpResponseStatus.NOT_FOUND).send(context.channel(), request)
    return true
  }

  if (!validateToken(request, context.channel(), urlDecoder)) {
    return true
  }

  for (pathHandler in WebServerPathHandler.EP_NAME.extensions) {
    LOG.catchAndLog {
      if (pathHandler.process(path, project, request, context, projectName, decodedPath, isCustomHost)) {
        return true
      }
    }
  }
  return false
}

private fun validateToken(request: HttpRequest, channel: Channel, urlDecoder: QueryStringDecoder): Boolean {
  val cookieString = request.headers().get(HttpHeaderNames.COOKIE)
  if (cookieString != null) {
    val cookies = ServerCookieDecoder.STRICT.decode(cookieString)
    for (cookie in cookies) {
      if (cookie.name() == STANDARD_COOKIE.name()) {
        if (cookie.value() == STANDARD_COOKIE.value()) {
          return true
        }
        break
      }
    }
  }

  val token = urlDecoder.parameters().get(TOKEN_PARAM_NAME)?.firstOrNull()
  val url = "${channel.uriScheme}://${request.host!!}${urlDecoder.path()}"
  if (token != null && tokens.getIfPresent(token) != null) {
    tokens.invalidate(token)
    // we redirect because it is not easy to change and maintain all places where we send response
    val response = HttpResponseStatus.TEMPORARY_REDIRECT.response()
    response.headers().add(HttpHeaderNames.LOCATION, url)
    response.headers().set(HttpHeaderNames.SET_COOKIE, ClientCookieEncoder.STRICT.encode(STANDARD_COOKIE))
    response.send(channel, request)
    return true
  }

  SwingUtilities.invokeAndWait {
    ProjectUtil.focusProjectWindow(null, true)

    if (MessageDialogBuilder
        .yesNo("", "Page '" + StringUtil.trimMiddle(url, 50) + "' requested without authorization, " +
            "\nyou can copy URL and open it in browser to trust it.")
        .icon(Messages.getWarningIcon())
        .yesText("Copy authorization URL to clipboard")
        .show() == Messages.YES) {
      CopyPasteManager.getInstance().setContents(StringSelection(url + "?" + TOKEN_PARAM_NAME + "=" + acquireToken()))
    }
  }

  HttpResponseStatus.UNAUTHORIZED.orInSafeMode(HttpResponseStatus.NOT_FOUND).send(channel, request)
  return false
}

private fun toIdeaPath(decodedPath: String, offset: Int): String? {
  // must be absolute path (relative to DOCUMENT_ROOT, i.e. scheme://authority/) to properly canonicalize
  val path = decodedPath.substring(offset)
  if (!path.startsWith('/')) {
    return null
  }
  return FileUtil.toCanonicalPath(path, '/').substring(1)
}

fun compareNameAndProjectBasePath(projectName: String, project: Project): Boolean {
  val basePath = project.basePath
  return basePath != null && endsWithName(basePath, projectName)
}

fun findIndexFile(basedir: VirtualFile): VirtualFile? {
  val children = basedir.children
  if (children == null || children.isEmpty()) {
    return null
  }

  for (indexNamePrefix in arrayOf("index.", "default.")) {
    var index: VirtualFile? = null
    val preferredName = indexNamePrefix + "html"
    for (child in children) {
      if (!child.isDirectory) {
        val name = child.name
        //noinspection IfStatementWithIdenticalBranches
        if (name == preferredName) {
          return child
        }
        else if (index == null && name.startsWith(indexNamePrefix)) {
          index = child
        }
      }
    }
    if (index != null) {
      return index
    }
  }
  return null
}

fun findIndexFile(basedir: Path): Path? {
  val children = basedir.directoryStreamIfExists({
    val name = it.fileName.toString()
    name.startsWith("index.") || name.startsWith("default.")
  }) { it.toList() } ?: return null

  for (indexNamePrefix in arrayOf("index.", "default.")) {
    var index: Path? = null
    val preferredName = "${indexNamePrefix}html"
    for (child in children) {
      if (!child.isDirectory()) {
        val name = child.fileName.toString()
        if (name == preferredName) {
          return child
        }
        else if (index == null && name.startsWith(indexNamePrefix)) {
          index = child
        }
      }
    }
    if (index != null) {
      return index
    }
  }
  return null
}

// is host loopback/any or network interface address (i.e. not custom domain)
// must be not used to check is host on local machine
internal fun isOwnHostName(host: String): Boolean {
  if (NetUtils.isLocalhost(host)) {
    return true
  }

  try {
    val address = InetAddress.getByName(host)
    if (host == address.hostAddress || host.equals(address.canonicalHostName, ignoreCase = true)) {
      return true
    }

    val localHostName = InetAddress.getLocalHost().hostName
    // WEB-8889
    // develar.local is own host name: develar. equals to "develar.labs.intellij.net" (canonical host name)
    return localHostName.equals(host, ignoreCase = true) || (host.endsWith(".local") && localHostName.regionMatches(0, host, 0, host.length - ".local".length, true))
  }
  catch (ignored: IOException) {
    return false
  }
}

internal fun canBeAccessedDirectly(path: String): Boolean {
  for (fileHandler in WebServerFileHandler.EP_NAME.extensions) {
    for (ext in fileHandler.pageFileExtensions) {
      if (FileUtilRt.extensionEquals(path, ext)) {
        return true
      }
    }
  }
  return false
}