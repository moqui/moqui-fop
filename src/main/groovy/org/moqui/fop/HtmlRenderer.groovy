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

import cz.vutbr.web.css.MediaSpec
import groovy.transform.CompileStatic
import org.fit.cssbox.css.CSSNorm
import org.fit.cssbox.css.DOMAnalyzer
import org.fit.cssbox.io.*
import org.fit.cssbox.layout.BrowserCanvas
import org.fit.cssbox.layout.BrowserConfig
import org.fit.cssbox.layout.Viewport
import org.fit.cssbox.render.SVGRenderer
import org.w3c.dom.Document
import org.xml.sax.SAXException

import javax.imageio.ImageIO
import java.awt.*
import java.nio.charset.StandardCharsets

@CompileStatic
class HtmlRenderer {
    private DocumentSource docSource = null
    private String mediaType = "screen"
    private Dimension windowSize = new Dimension(992, 1284) // NOTE: 992 is the bootstrap md min-width, 1284 = (11/8.5)*992
    private boolean cropWindow = false
    private boolean loadImages = true
    private boolean loadBackgroundImages = false

    HtmlRenderer() { }

    HtmlRenderer setWindowSize(Dimension size) { windowSize = size; return this }
    /** Default is 'screen', 'print' also valid */
    HtmlRenderer setMediaType(String media) { mediaType = media; return this }
    /** Defaults to false (don't crop at window size) */
    HtmlRenderer setCropWindow(boolean crop) { cropWindow = crop; return this }
    /** Defaults to true (include content), false (don't include background images) */
    HtmlRenderer setLoadImages(boolean content, boolean background) {
        loadImages = content
        loadBackgroundImages = background
        return this
    }

    HtmlRenderer setSourceUrl(String urlString) {
        if (!urlString.startsWith("http:") && !urlString.startsWith("https:") && !urlString.startsWith("ftp:") &&
                !urlString.startsWith("file:")) urlString = "http://" + urlString
        docSource = new DefaultDocumentSource(urlString)
        return this
    }
    HtmlRenderer setSourceString(String htmlString, URL url) {
        ByteArrayInputStream is = new ByteArrayInputStream(htmlString.getBytes(StandardCharsets.UTF_8))
        docSource = new StreamDocumentSource(is, url, "text/html")
        return this
    }

    BrowserCanvas makeContentCanvas() {
        if (docSource == null) throw new IllegalStateException("Cannot render, source not set")

        //Parse the input document
        DOMSource parser = new DefaultDOMSource(docSource)
        Document doc = parser.parse()

        //create the media specification
        MediaSpec media = new MediaSpec(mediaType)
        media.setDimensions(windowSize.width as float, windowSize.height as float)
        media.setDeviceDimensions(windowSize.width as float, windowSize.height as float)

        //Create the CSS analyzer
        DOMAnalyzer da = new DOMAnalyzer(doc, docSource.getURL())
        da.setMediaSpec(media)
        da.attributesToStyles() //convert the HTML presentation attributes to inline styles
        da.addStyleSheet(null, CSSNorm.stdStyleSheet(), DOMAnalyzer.Origin.AGENT) //use the standard style sheet
        da.addStyleSheet(null, CSSNorm.userStyleSheet(), DOMAnalyzer.Origin.AGENT) //use the additional style sheet
        da.addStyleSheet(null, CSSNorm.formsStyleSheet(), DOMAnalyzer.Origin.AGENT) //render form fields using css
        da.getStyleSheets() //load the author style sheets

        BrowserCanvas contentCanvas = new BrowserCanvas(da.getRoot(), da, docSource.getURL())
        contentCanvas.setAutoMediaUpdate(false) //we have a correct media specification, do not update
        contentCanvas.getConfig().setClipViewport(cropWindow)
        contentCanvas.getConfig().setLoadImages(loadImages)
        contentCanvas.getConfig().setLoadBackgroundImages(loadBackgroundImages)

        setDefaultFonts(contentCanvas.getConfig())
        contentCanvas.createLayout(windowSize)

        return contentCanvas
    }

    void renderSvg(Writer out) throws IOException, SAXException {
        try {
            BrowserCanvas contentCanvas = makeContentCanvas()
            Viewport vp = contentCanvas.getViewport()
            //obtain the viewport bounds depending on whether we are clipping to viewport size or using the whole page
            Rectangle contentBounds = vp.getClippedContentBounds()
            SVGRenderer render = new SVGRenderer(contentBounds.width as int, contentBounds.height as int, out)
            try { vp.draw(render) }
            finally { render.close() }
        } finally {
            if (docSource != null) docSource.close()
        }
    }
    /** Render to an image, imageType may be standard Java ImageIO values including 'png' (default), 'jpeg', 'tiff', etc */
    void renderImage(OutputStream out, String imageType) throws IOException, SAXException {
        if (!imageType) imageType = "png"
        try {
            BrowserCanvas contentCanvas = makeContentCanvas()
            ImageIO.write(contentCanvas.getImage(), imageType, out)
        } finally {
            if (docSource != null) docSource.close()
        }
    }

    /** Sets some common fonts as the defaults for generic font families. */
    static protected void setDefaultFonts(BrowserConfig config) {
        config.setDefaultFont(Font.SERIF, "Times New Roman")
        config.setDefaultFont(Font.SANS_SERIF, "Arial")
        config.setDefaultFont(Font.MONOSPACED, "Courier New")
    }
}
