import platform

def current_cpu():
    """Return current cpu architecture."""
    return platform.processor() 