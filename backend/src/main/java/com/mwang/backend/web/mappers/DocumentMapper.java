package com.mwang.backend.web.mappers;

import com.mwang.backend.domain.Document;
import com.mwang.backend.web.model.DocumentDto;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring", uses = DateMapper.class)
public interface DocumentMapper {
    DocumentDto documentToDocumentDto(Document document);
    Document documentDtoToDocument(DocumentDto documentDto);
}
