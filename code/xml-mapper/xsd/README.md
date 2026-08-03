# New Draft XSD schemas

## Semantic
* Basis ModelUsed primarily by eFTI Platforms and Competent Authority (CA) applications.
* Focuses heavily on CMDS definition and validation using denormalized (exploded) data types.
* Contains subset-mapping rules embedded directly as <xs:appinfo> annotations.
* These annotations are intended for design-time code generation and are not processed during runtime.

## Normalized
* Used by eFTI Gates for message routing and validation.
* Focuses on the message structure rather than the inner CMDS (which gates may treat as a validated "blob").
* Does not contain subset annotations.
* Uses normalized (summarized) global subtypes (e.g., a shared postalAddress_type).

## Defined eDelivery messages

* FTI004UploadIdentifierRequest -> FTI029UploadIdentifierResponse
* FTI009GetCmdsRequest -> FTI010GetCmdsResponse
* FTI019SearchIdentifierRequest -> FTI021SearchIdentifierResponse
* FTI025LodgeFollowUpCommRequest -> FTI030LodgeFollowUpCommResponse

Each Normalized/FTI* message folder contains a generated `sample.xml`.
