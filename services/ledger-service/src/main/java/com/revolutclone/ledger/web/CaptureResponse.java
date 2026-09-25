package com.revolutclone.ledger.web;

import com.revolutclone.ledger.reservation.CaptureResult;
import java.util.List;
import java.util.UUID;

public record CaptureResponse(
        UUID entryId,
        long capturedMinor,
        long releasedRemainderMinor,
        List<PostingResponse> postings) {

    static CaptureResponse from(CaptureResult result) {
        return new CaptureResponse(result.entry().getId(), result.capturedMinor(), result.releasedRemainderMinor(),
                result.postings().stream().map(PostingResponse::from).toList());
    }
}
