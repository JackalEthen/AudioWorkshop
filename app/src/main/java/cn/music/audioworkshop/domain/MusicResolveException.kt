package cn.music.audioworkshop.domain

/** 解析失败。消息是给用户看的，不要写内部细节。 */
class MusicResolveException(message: String) : Exception(message)
