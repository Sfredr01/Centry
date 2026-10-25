package com.centry.web;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.io.UnsupportedEncodingException;

/** Parses a MockMvc response body so tests can assert structure instead of exact markup. */
final class Html {

    private Html() {
    }

    static Document of(ResultActions result) throws UnsupportedEncodingException {
        return of(result.andReturn());
    }

    static Document of(MvcResult result) throws UnsupportedEncodingException {
        return Jsoup.parse(result.getResponse().getContentAsString());
    }
}
