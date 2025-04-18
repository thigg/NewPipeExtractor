package uk.co.flypig;

/*
 * Created by David Llewellyn-Jones on 2025-02-15.
 *
 * Copyright (C) 2025 David Llewellyn-Jones <david@flypig.co.uk>
 *
 * This is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this code.  If not, see <http://www.gnu.org/licenses/>.
 */

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.dslplatform.json.DslJson;
import okhttp3.ConnectionSpec;
import okhttp3.OkHttpClient;
import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.c.function.CEntryPoint;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.nativeimage.c.type.CTypeConversion;
import org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.comments.CommentsInfo;
import org.schabi.newpipe.extractor.comments.CommentsInfoItem;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.search.SearchInfo;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamExtractor;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.extractor.suggestion.SuggestionExtractor;


final class MethodInfo<In, Out> {
    public MethodInfo(Function<In, Out> method, Class<In> className) {
        this.method = method;
        this.className = className;
    }

    Function<In, Out> method;
    Class<In> className;
}

final class Main {
    static DslJson<Object> jsonMapper = new DslJson<>();
    static Map<String, MethodInfo> methodInfo;
    static CCharPointer emptyString;
    static DownloaderImpl downloader = null;
    static HashMap<String, StreamingService> services;

    private Main() {
        throw new UnsupportedOperationException();
    }

    public static void main(final String[] args) {
        throw new UnsupportedOperationException();
    }

    private static <In, Out> CCharPointer call(Function<In, Out> method, CCharPointer in, Class<In> className) {
        String output = new String();
        try {
            final String parameters = CTypeConversion.toJavaString(in);
            final In input = Main.jsonMapper.deserialize(className, new ByteArrayInputStream(parameters.getBytes(StandardCharsets.UTF_8)));
            final Out result = method.apply(input);
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            Main.jsonMapper.serialize(result, outputStream);
            output = outputStream.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.out.println("Exception during Serialization: " + e);
        }

        final CTypeConversion.CCharPointerHolder holder = CTypeConversion.toCString(output);
        return holder.get();
    }

    private static StreamingService convertStringToService(final String service) {
        StreamingService serviceId = ServiceList.YouTube;
        if (services.containsKey(service)) {
            serviceId = services.get(service);
        }
        return serviceId;
    }

    @CEntryPoint(name = "init")
    static void init(IsolateThread thread) {
            // Set up static data
        // my IDE complains that toCString returns an AutoClosable that is not used
        // I think on close the CString would be free'd
        // I guess that means this warns of a memory leak
        // in this case this is intended. I wonder when the result of invoke is supposed to be free'd
            emptyString = CTypeConversion.toCString("").get();

        // Create the HTTP client
        final OkHttpClient.Builder builder = new OkHttpClient.Builder();
        final OkHttpClient client = builder
            .connectionSpecs(Arrays.asList(ConnectionSpec.RESTRICTED_TLS))
            .build();
        downloader = DownloaderImpl.init(builder);
        NewPipe.init(downloader);

        // The available services
        services = new HashMap<String, StreamingService>();
        services.put("Bandcamp", ServiceList.Bandcamp);
        services.put("MediaCCC", ServiceList.MediaCCC);
        services.put("PeerTube", ServiceList.PeerTube);
        services.put("SoundCloud", ServiceList.SoundCloud);
        services.put("YouTube", ServiceList.YouTube);

        // The exposed functions
        methodInfo = new HashMap<>();
        methodInfo.put("downloadExtract", new MethodInfo<DownloadLocater,
            DownloadExtracted>(Main::downloadExtract, DownloadLocater.class)
        );
        methodInfo.put("tearDown", new MethodInfo<ParamNone,
            ParamNone>(Main::tearDown, ParamNone.class)
        );
        methodInfo.put("getSuggestions", new MethodInfo<SuggestionsQuery,
            SuggestionsResponse>(Main::getSuggestions, SuggestionsQuery.class)
        );
        methodInfo.put("searchFor", new MethodInfo<SearchQuery,
            SearchInfoResponse>(Main::searchFor, SearchQuery.class)
        );
        methodInfo.put("getMoreSearchItems", new MethodInfo<SearchQuery,
            InfoItemsPageResponse>(Main::getMoreSearchItems, SearchQuery.class)
        );
        methodInfo.put("getAvailableContentFilter", new MethodInfo<ParamString,
            ParamStringList>(Main::getAvailableContentFilter, ParamString.class)
        );
        methodInfo.put("getCommentsInfo", new MethodInfo<CommentsInfoQuery,
            CommentsInfoResponse>(Main::getCommentsInfo, CommentsInfoQuery.class)
        );
        methodInfo.put("getMoreCommentItems", new MethodInfo<CommentsInfoQuery,
            InfoItemsPageCommentsResponse>(Main::getMoreCommentItems, CommentsInfoQuery.class)
        );
    }

    @CEntryPoint(name = "invoke")
    public static CCharPointer invoke(IsolateThread thread, CCharPointer methodNamePtr, CCharPointer in) {
        final String methodName = CTypeConversion.toJavaString(methodNamePtr);
        CCharPointer result = emptyString;
        if (methodInfo.containsKey(methodName)) {
            final MethodInfo info = methodInfo.get(methodName);
            result = call(info.method, in, info.className);
        } else {
            System.out.println("Invocation failed, unknown method: " + methodName);
        }
        return result;
    }

    /*
    * The exposed NewPipe Extractor API starts here
    */

    private static DownloadExtracted downloadExtract(DownloadLocater download) {
        DownloadExtracted extracted = new DownloadExtracted();
        StreamingService service = convertStringToService(download.service);

        try {
            final StreamExtractor extractor = service.getStreamExtractor(download.url);
            extractor.fetchPage();

            extracted.name = extractor.getName();
            extracted.uploaderName = extractor.getUploaderName();
            extracted.category = extractor.getCategory();
            extracted.likeCount = extractor.getLikeCount();
            extracted.viewCount = extractor.getViewCount();

            final java.util.List<VideoStream> videoStreams = extractor.getVideoStreams();
            for (final VideoStream stream : videoStreams) {
                extracted.content = stream.getContent();
            }
            if (extracted.content == null) {
                final java.util.List<AudioStream> audioStreams = extractor.getAudioStreams();
                for (final AudioStream stream : audioStreams) {
                    extracted.content = stream.getContent();
                }
            }
        }
        catch (final Exception e) {
            System.out.println("Exception: " + e);
        }

        return extracted;
    }

    private static ParamNone tearDown(ParamNone empty) {
        if (downloader != null) {
            try {
                downloader.teardown();
            }
            catch (final IOException e) {
                System.out.println("IOException in tearDown: " + e);
            }
            downloader = null;
        }
        return empty;
    }

    private static SuggestionsResponse getSuggestions(SuggestionsQuery query) {
        StreamingService serviceId = convertStringToService(query.service);

        List<String> suggestions = Collections.emptyList();
        try {
            final SuggestionExtractor extractor = serviceId.getSuggestionExtractor();
            if (extractor != null) {
                suggestions = extractor.suggestionList(query.query);
            }
        }
        catch (final IOException e) {
            System.out.println("IOException in getSuggestions: " + e);
        }
        catch (final ExtractionException e) {
            System.out.println("ExtractionException in getSuggestions: " + e);
        }
        final SuggestionsResponse response = new SuggestionsResponse(suggestions);

        return response;
    }

    private static SearchInfoResponse searchFor(SearchQuery query) {
        StreamingService serviceId = convertStringToService(query.service);

        SearchInfoResponse response = new SearchInfoResponse();
        try {
            SearchInfo result = SearchInfo.getInfo(
                serviceId,
                serviceId.getSearchQHFactory().fromQuery(
                    query.searchString,
                    query.contentFilter,
                    query.sortFilter
                )
            );
            final List<Throwable> exceptions = result.getErrors();
            if (!exceptions.isEmpty()) {
                System.out.println("Search exceptions: " + exceptions.size());
                System.out.println("Search exception: " + exceptions.get(0));
                System.out.println("Search exception stack trace:");
                exceptions.get(0).printStackTrace();
            }
            response = new SearchInfoResponse(
                result.getSearchString(),
                result.getSearchSuggestion(),
                result.isCorrectedSearch(),
                result.getNextPage(),
                result.getContentFilters(),
                result.getSortFilter(),
                result.getRelatedItems(),
                result.getMetaInfo()
            );
        }
        catch (final IOException e) {
            System.out.println("IOException in searchFor: " + e);
        }
        catch (final ExtractionException e) {
            System.out.println("ExtractionException in searchFor: " + e);
        }

        return response;
    }

    private static InfoItemsPageResponse getMoreSearchItems(SearchQuery query) {
        StreamingService serviceId = convertStringToService(query.service);

        InfoItemsPageResponse response = new InfoItemsPageResponse();
        try {
            InfoItemsPage result = SearchInfo.getMoreItems(
                serviceId,
                serviceId.getSearchQHFactory().fromQuery(
                    query.searchString,
                    query.contentFilter,
                    query.sortFilter
                ),
                query.page
            );
            response = new InfoItemsPageResponse(
                result.getNextPage(),
                result.getItems()
            );
        }
        catch (final IOException e) {
            System.out.println("IOException in getMoreSearchItems: " + e);
        }
        catch (final ExtractionException e) {
            System.out.println("ExtractionException in getMoreSearchItems: " + e);
        }

        return response;
    }

    private static ParamStringList getAvailableContentFilter(ParamString service) {
        StreamingService serviceId = convertStringToService(service.string);
        ParamStringList result = new ParamStringList();
        result.stringList = serviceId.getSearchQHFactory().getAvailableContentFilter();
        return result;
    }

    private static CommentsInfoResponse getCommentsInfo(CommentsInfoQuery query) {
        StreamingService serviceId = convertStringToService(query.service);

        CommentsInfoResponse response = new CommentsInfoResponse();
        try {
            CommentsInfo result = CommentsInfo.getInfo(serviceId, query.url);
            if (result != null) {
                final List<Throwable> exceptions = result.getErrors();
                if (!exceptions.isEmpty()) {
                    System.out.println("Comments exceptions: " + exceptions.size());
                    System.out.println("Comments exception: " + exceptions.get(0));
                    System.out.println("Comments exception stack trace:");
                    exceptions.get(0).printStackTrace();
                }
                response = new CommentsInfoResponse(
                    result.getNextPage(),
                    result.getContentFilters(),
                    result.getSortFilter(),
                    result.getRelatedItems()
                );
            }
        }
        catch (final IOException e) {
            System.out.println("IOException in getCommentsInfo: " + e);
        }
        catch (final ExtractionException e) {
            System.out.println("ExtractionException in getCommentsInfo: " + e);
        }

        return response;
    }

    private static InfoItemsPageCommentsResponse getMoreCommentItems(CommentsInfoQuery query) {
        StreamingService serviceId = convertStringToService(query.service);

        InfoItemsPageCommentsResponse response = new InfoItemsPageCommentsResponse();
        try {
            InfoItemsPage<CommentsInfoItem> result = CommentsInfo.getMoreItems(
                serviceId,
                query.url,
                query.page
            );
            response = new InfoItemsPageCommentsResponse(
                result.getNextPage(),
                result.getItems()
            );
        }
        catch (final IOException e) {
            System.out.println("IOException in getMoreCommentItems: " + e);
        }
        catch (final ExtractionException e) {
            System.out.println("ExtractionException in getMoreCommentItems: " + e);
        }

        return response;
    }
}

