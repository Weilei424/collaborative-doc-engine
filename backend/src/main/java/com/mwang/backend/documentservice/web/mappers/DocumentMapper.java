package com.mwang.backend.documentservice.web.mappers;

import com.mwang.backend.documentservice.domain.Document;
import com.mwang.backend.documentservice.web.model.DocumentDto;
import org.mapstruct.Mapper;

@Mapper(uses = DateMapper.class)
public interface DocumentMapper {
    DocumentDto documentToDocumentDto(Document document);
    Document documentDtoToDocument(DocumentDto documentDto);
}
