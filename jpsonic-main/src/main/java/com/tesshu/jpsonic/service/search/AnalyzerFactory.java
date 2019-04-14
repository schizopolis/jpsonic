/*
 This file is part of Jpsonic.

 Jpsonic is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

 Jpsonic is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with Jpsonic.  If not, see <http://www.gnu.org/licenses/>.

 Copyright 2019 (C) tesshu.com
 */
package com.tesshu.jpsonic.service.search;

import com.tesshu.jpsonic.service.search.IndexType.FieldNames;
import com.tesshu.jpsonic.service.search.analysis.HiraganaTermStemFilterFactory;
import com.tesshu.jpsonic.service.search.analysis.Id3ArtistTokenizerFactory;
import com.tesshu.jpsonic.service.search.analysis.PunctuationStemFilterFactory;
import com.tesshu.jpsonic.service.search.analysis.ToHiraganaFilterFactory;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.cjk.CJKWidthFilterFactory;
import org.apache.lucene.analysis.core.KeywordTokenizerFactory;
import org.apache.lucene.analysis.core.LowerCaseFilterFactory;
import org.apache.lucene.analysis.core.StopFilterFactory;
import org.apache.lucene.analysis.custom.CustomAnalyzer;
import org.apache.lucene.analysis.custom.CustomAnalyzer.Builder;
import org.apache.lucene.analysis.ja.JapanesePartOfSpeechStopFilterFactory;
import org.apache.lucene.analysis.ja.JapaneseTokenizerFactory;
import org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilterFactory;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.pattern.PatternReplaceFilterFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Analyzer provider. This class is a division of what was once part of
 * SearchService and added functionality.
 * 
 * Analyzer can be closed but is a reuse premise. It is held in this class.
 * 
 * Some of the query parses performed in legacy services are defined in
 * QueryAnalyzer. Most of the actual query parsing is done with QueryFactory.
 */
public final class AnalyzerFactory {

    private static AnalyzerFactory instance;

    private static final Logger LOG = LoggerFactory.getLogger(AnalyzerFactory.class);
    
    private static final Object l = new Object();

    /**
     * Returns an instance of AnalyzerFactory.
     * 
     * @return AnalyzerFactory instance
     */
    public static AnalyzerFactory getInstance() {
        if (null == instance) {
            synchronized (l) {
                if (instance == null) {
                    instance = new AnalyzerFactory();
                }
            }
        }
        return instance;
    }

    private Analyzer analyzer;

    private Analyzer queryAnalyzer;
    
    private final String stopTags = "org/apache/lucene/analysis/ja/stoptags.txt";

    private final String stopWords = "com/tesshu/jpsonic/service/stopwords.txt";

    private AnalyzerFactory() {
    }

    private CustomAnalyzer.Builder addWildCard(CustomAnalyzer.Builder builder) throws IOException {
        builder.addTokenFilter(PatternReplaceFilterFactory.class, "pattern", "(^(?!.*\\*$).+$)", "replacement", "$1*", "replace", "first");
        return builder;
    }

    private CustomAnalyzer.Builder basicFilters(CustomAnalyzer.Builder builder) throws IOException {
        builder.addTokenFilter(CJKWidthFilterFactory.class) // before StopFilter
                .addTokenFilter(StopFilterFactory.class, "words", stopWords, "ignoreCase", "true")
                .addTokenFilter(JapanesePartOfSpeechStopFilterFactory.class, "tags", stopTags)
                .addTokenFilter(ASCIIFoldingFilterFactory.class, "preserveOriginal", "false")
                .addTokenFilter(LowerCaseFilterFactory.class);
        return builder;
    }

    private Builder createKeyAnalyzerBuilder() throws IOException {
        return createPathAnalyzerBuilder().addTokenFilter(PunctuationStemFilterFactory.class);
    }

    private Builder createMultiTokenAnalyzerBuilder() throws IOException {
        CustomAnalyzer.Builder builder = CustomAnalyzer.builder().withTokenizer(JapaneseTokenizerFactory.class);
        builder = basicFilters(builder);
        return builder;
    }

    private Builder createOnlyHiraganaAnalyzerBuilder() throws IOException {
        return createSingleTokenAnalyzerBuilder()
                .addTokenFilter(HiraganaTermStemFilterFactory.class, "passableOnlyAllHiragana", "true");
    }

    private Builder createOtherThanHiraganaAnalyzerBuilder() throws IOException {
        return createSingleTokenAnalyzerBuilder()
                .addTokenFilter(HiraganaTermStemFilterFactory.class, "passableOnlyAllHiragana", "false");
    }

    private Builder createPathAnalyzerBuilder() throws IOException {
        return CustomAnalyzer.builder().withTokenizer(KeywordTokenizerFactory.class);
    }

    private Builder createSingleTokenAnalyzerBuilder() throws IOException {
        CustomAnalyzer.Builder builder = createPathAnalyzerBuilder();
        builder = basicFilters(builder);
        builder = builder.addTokenFilter(PunctuationStemFilterFactory.class);
        return builder;
    }

    private Builder createId3ArtistAnalyzerBuilder() throws IOException {  
        CustomAnalyzer.Builder builder = CustomAnalyzer.builder().withTokenizer(Id3ArtistTokenizerFactory.class);
        builder = basicFilters(builder)
                .addTokenFilter(PunctuationStemFilterFactory.class)
                .addTokenFilter(ToHiraganaFilterFactory.class);
        return builder;
    }

    /**
     * Return analyzer.
     * 
     * @return analyzer for index
     */
    @SuppressWarnings("deprecation")
    public Analyzer getAnalyzer() {
        if (null == this.analyzer) {
            try {

                Analyzer path = createPathAnalyzerBuilder().build();
                Analyzer key = createKeyAnalyzerBuilder().build();
                Analyzer id3Artist = createId3ArtistAnalyzerBuilder().build();
                Analyzer multiTerm = createMultiTokenAnalyzerBuilder().build();
                Analyzer onlyHiragana = createOnlyHiraganaAnalyzerBuilder().build();
                Analyzer otherThanHiragana = createOtherThanHiraganaAnalyzerBuilder().build();

                Map<String, Analyzer> analyzerMap = new HashMap<String, Analyzer>();
                analyzerMap.put(FieldNames.FOLDER, path);
                analyzerMap.put(FieldNames.GENRE, key);
                analyzerMap.put(FieldNames.MEDIA_TYPE, key);
                analyzerMap.put(FieldNames.ARTIST_READING, id3Artist);
                analyzerMap.put(FieldNames.ALBUM_READING_HIRAGANA, onlyHiragana);
                analyzerMap.put(FieldNames.TITLE_READING_HIRAGANA, onlyHiragana);
                analyzerMap.put(FieldNames.ALBUM_FULL, otherThanHiragana);

                this.analyzer = new PerFieldAnalyzerWrapper(multiTerm, analyzerMap);

            } catch (IOException e) {
                LOG.error("Error when initializing Analyzer.", e);
            }
        }
        return this.analyzer;
    }

    /**
     * Return analyzer.
     * 
     * @return analyzer for index
     */
    @SuppressWarnings("deprecation")
    public Analyzer getQueryAnalyzer() {
        if (null == this.queryAnalyzer) {
            try {

                Analyzer path = createPathAnalyzerBuilder().build();
                Analyzer key = createKeyAnalyzerBuilder().build();
                Analyzer id3Artist = addWildCard(createId3ArtistAnalyzerBuilder()).build();
                Analyzer multiTerm = addWildCard(createMultiTokenAnalyzerBuilder()).build();
                Analyzer onlyHiragana = addWildCard(createOnlyHiraganaAnalyzerBuilder()).build();
                Analyzer otherThanHiragana = addWildCard(createOtherThanHiraganaAnalyzerBuilder()).build();

                Map<String, Analyzer> analyzerMap = new HashMap<String, Analyzer>();
                analyzerMap.put(FieldNames.FOLDER, path);
                analyzerMap.put(FieldNames.GENRE, key);
                analyzerMap.put(FieldNames.MEDIA_TYPE, key);
                analyzerMap.put(FieldNames.ARTIST_READING, id3Artist);
                analyzerMap.put(FieldNames.ALBUM_READING_HIRAGANA, onlyHiragana);
                analyzerMap.put(FieldNames.TITLE_READING_HIRAGANA, onlyHiragana);
                analyzerMap.put(FieldNames.ALBUM_FULL, otherThanHiragana);

                this.queryAnalyzer = new PerFieldAnalyzerWrapper(multiTerm, analyzerMap);

            } catch (IOException e) {
                LOG.error("Error when initializing Analyzer.", e);
            }
        }
        return this.queryAnalyzer;
    }

}