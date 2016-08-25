/*
 * This software is in the public domain under CC0 1.0 Universal plus a 
 * Grant of Patent License.
 * 
 * To the extent possible under law, the author(s) have dedicated all
 * copyright and related and neighboring rights to this software to the
 * public domain worldwide. This software is distributed without any
 * warranty.
 * 
 * You should have received a copy of the CC0 Public Domain Dedication
 * along with this software (see the LICENSE.md file). If not, see
 * <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org.moqui.fop

import groovy.transform.CompileStatic
import org.apache.batik.transcoder.TranscoderInput
import org.apache.batik.transcoder.TranscoderOutput
import org.apache.fop.svg.PDFTranscoder

import org.moqui.context.ArtifactAuthorizationException
import org.moqui.context.ArtifactTarpitException
import org.moqui.context.ExecutionContext
import org.moqui.impl.StupidUtilities
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.impl.webapp.ScreenResourceNotFoundException
import org.moqui.screen.ScreenRender
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.servlet.ServletException
import javax.servlet.http.HttpServlet
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import java.awt.Dimension

@CompileStatic
class HtmlRenderServlet extends HttpServlet {
    protected final static Logger logger = LoggerFactory.getLogger(HtmlRenderServlet.class)

    static final Map<String, Dimension> pageFormatDimensions = [A0:new Dimension(2348, 3370), A1:new Dimension(1648, 2348),
            A2:new Dimension(1191, 1648), A3:new Dimension(842, 1191), A4:new Dimension(595, 842),
            A5:new Dimension(420, 595), A6:new Dimension(298, 420), LETTER:new Dimension(612, 792), LETTERP:new Dimension(792, 612),
            LETTERB:new Dimension(992, 1284), LETTERBP:new Dimension(1284, 992)
    ]

    HtmlRenderServlet() {
        super()
    }

    @Override
    void doPost(HttpServletRequest request, HttpServletResponse response) { doScreenRequest(request, response) }

    @Override
    void doGet(HttpServletRequest request, HttpServletResponse response) { doScreenRequest(request, response) }

    void doScreenRequest(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        ExecutionContextFactoryImpl ecfi =
                (ExecutionContextFactoryImpl) getServletContext().getAttribute("executionContextFactory")
        String moquiWebappName = getServletContext().getInitParameter("moqui-name")

        String pathInfo = request.getPathInfo()
        long startTime = System.currentTimeMillis()

        if (logger.traceEnabled) logger.trace("Start request to [${pathInfo}] at time [${startTime}] in session [${request.session.id}] thread [${Thread.currentThread().id}:${Thread.currentThread().name}]")

        ExecutionContext ec = ecfi.getExecutionContext()
        ec.initWebFacade(moquiWebappName, request, response)
        ec.web.requestAttributes.put("moquiRequestStartTime", startTime)
        String htmlUrl = ec.web.getWebappRootUrl(true, null) + "/" + pathInfo

        String filename = ec.web.parameters.get("filename") as String
        String contentType = ec.web.requestParameters."contentType" as String ?: "application/pdf"
        if (!"application/pdf".equals(contentType) && !"image/svg+xml".equals(contentType)) {
            logger.info("In HtmlRenderSerlvet content type ${contentType} not valid, setting to application/pdf")
            contentType = "application/pdf"
        }

        String pageFormat = ec.web.requestParameters."pageFormat" ?: "LETTERB"
        if (!pageFormatDimensions.containsKey(pageFormat)) pageFormat = "LETTERB"
        Dimension windowSize = pageFormatDimensions.get(pageFormat)

        String htmlText = null
        try {
            ScreenRender sr = ec.screen.makeRender().webappName(moquiWebappName).renderMode("html")
                    .rootScreenFromHost(request.getServerName()).screenPath(pathInfo.split("/") as List)
            htmlText = sr.render()

            // logger.warn("======== HTML content from ${pathInfo}:\n${htmlText}")
            if (logger.traceEnabled) logger.trace("HTML content:\n${htmlText}")

            response.setContentType(contentType)

            if (filename) {
                String utfFilename = StupidUtilities.encodeAsciiFilename(filename)
                response.addHeader("Content-Disposition", "attachment; filename=\"${filename}\"; filename*=utf-8''${utfFilename}")
            } else {
                response.addHeader("Content-Disposition", "inline")
            }

            HtmlRenderer htmlRenderer = new HtmlRenderer().setWindowSize(windowSize)
            htmlRenderer.setSourceString(htmlText, new URL(htmlUrl))

            // special case disable authz for resource access
            boolean enableAuthz = !ecfi.getExecutionContext().getArtifactExecution().disableAuthz()
            try {
                if ("image/svg+xml".equals(contentType)) {
                    htmlRenderer.renderSvg(response.getWriter())
                } else {
                    StringWriter svgWriter = new StringWriter()
                    htmlRenderer.renderSvg(svgWriter)

                    PDFTranscoder transcoder = new PDFTranscoder();
                    // NOTE: any way to get a StringReader without going through String?
                    // (copy StringWriter to String, make StringReader from String)
                    // StringWriter.getBuffer() gets a StringBuffer, but would still have to go to String for StringReader
                    String svgString = svgWriter.toString()
                    if (logger.traceEnabled) logger.trace("Produced SVG:\n${svgString}")
                    transcoder.transcode(new TranscoderInput(new StringReader(svgString)),
                            new TranscoderOutput(response.getOutputStream()));
                }
            } finally {
                if (enableAuthz) ecfi.getExecutionContext().getArtifactExecution().enableAuthz()
            }
        } catch (ArtifactAuthorizationException e) {
            // SC_UNAUTHORIZED 401 used when authc/login fails, use SC_FORBIDDEN 403 for authz failures
            // See ScreenRenderImpl.checkWebappSettings for authc and SC_UNAUTHORIZED handling
            logger.warn((String) "Web Access Forbidden (no authz): " + e.message)
            response.sendError(HttpServletResponse.SC_FORBIDDEN, e.message)
        } catch (ArtifactTarpitException e) {
            logger.warn((String) "Web Too Many Requests (tarpit): " + e.message)
            if (e.getRetryAfterSeconds()) response.addIntHeader("Retry-After", e.getRetryAfterSeconds())
            // NOTE: there is no constant on HttpServletResponse for 429; see RFC 6585 for details
            response.sendError(429, e.message)
        } catch (ScreenResourceNotFoundException e) {
            logger.warn((String) "Web Resource Not Found: " + e.message)
            response.sendError(HttpServletResponse.SC_NOT_FOUND, e.message)
        } catch (Throwable t) {
            logger.error("Error transforming HTML content:\n${htmlText}", t)
            if (ec.message.hasError()) {
                String errorsString = ec.message.errorsString
                logger.error(errorsString, t)
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, errorsString)
            } else {
                throw t
            }
        } finally {
            // make sure everything is cleaned up
            ec.destroy()
        }

        if (logger.infoEnabled) logger.info("Finished HTML Render request to ${pathInfo}, content type ${response.getContentType()} in ${System.currentTimeMillis()-startTime}ms; session ${request.session.id} thread ${Thread.currentThread().id}:${Thread.currentThread().name}")
    }
}
