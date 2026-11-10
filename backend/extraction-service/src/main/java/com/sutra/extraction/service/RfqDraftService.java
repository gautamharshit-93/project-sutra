package com.sutra.extraction.service;

import com.sutra.api.rfq.RfqDraftResponses.RfqDraft;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Reads an RFQ document (PDF, scanned PDF, Word, Excel) or pasted text into a draft with Groq. Nothing is saved:
 * the buyer checks the draft and creates the RFQ in the RFQ service.
 */
public interface RfqDraftService {

    RfqDraft fromFiles(List<MultipartFile> files);

    RfqDraft fromText(String text);
}
