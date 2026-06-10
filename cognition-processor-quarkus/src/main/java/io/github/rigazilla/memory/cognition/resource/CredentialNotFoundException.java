package io.github.rigazilla.memory.cognition.resource;

/**
 * Exception thrown when a credential reference cannot be resolved.
 * This typically occurs when an environment variable or secret is not found.
 */
public class CredentialNotFoundException extends RuntimeException {
    
    private final String credentialRef;
    
    public CredentialNotFoundException(String credentialRef) {
        super("Credential not found: " + credentialRef);
        this.credentialRef = credentialRef;
    }
    
    public CredentialNotFoundException(String credentialRef, Throwable cause) {
        super("Credential not found: " + credentialRef, cause);
        this.credentialRef = credentialRef;
    }
    
    public CredentialNotFoundException(String credentialRef, String message) {
        super(message);
        this.credentialRef = credentialRef;
    }
    
    /**
     * Get the credential reference that could not be resolved.
     * 
     * @return The credential reference (e.g., "OPENAI_API_KEY")
     */
    public String getCredentialRef() {
        return credentialRef;
    }
}
