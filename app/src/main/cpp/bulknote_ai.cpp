#include <jni.h>
#include <string>
#include <vector>
#include <mutex>
#include "llama.h"
static llama_model* g_model=nullptr;
static llama_context* g_ctx=nullptr;
static std::mutex g_mutex;
static std::string fromJ(JNIEnv* e,jstring s){if(!s)return {};const char*p=e->GetStringUTFChars(s,nullptr);std::string v=p?p:"";if(p)e->ReleaseStringUTFChars(s,p);return v;}
static void releaseAll(){if(g_ctx){llama_free(g_ctx);g_ctx=nullptr;}if(g_model){llama_model_free(g_model);g_model=nullptr;}}
extern "C" JNIEXPORT jint JNICALL Java_com_bulknote_app_MainActivity_00024Bridge_nativeLoad(JNIEnv* env,jobject,jstring path){
 std::lock_guard<std::mutex> lock(g_mutex);releaseAll();std::string p=fromJ(env,path);llama_backend_init();
 auto mp=llama_model_default_params();g_model=llama_model_load_from_file(p.c_str(),mp);if(!g_model)return 1;
 auto cp=llama_context_default_params();cp.n_ctx=2048;cp.n_batch=512;cp.n_threads=4;cp.n_threads_batch=4;
 g_ctx=llama_init_from_model(g_model,cp);if(!g_ctx){releaseAll();return 2;}return 0;
}
extern "C" JNIEXPORT jstring JNICALL Java_com_bulknote_app_MainActivity_00024Bridge_nativeGenerate(JNIEnv* env,jobject,jstring prompt){
 std::lock_guard<std::mutex> lock(g_mutex);if(!g_model||!g_ctx)return env->NewStringUTF("모델이 아직 준비되지 않았어요.");
 std::string user=fromJ(env,prompt);std::string full="<|im_start|>user\n"+user+"<|im_end|>\n<|im_start|>assistant\n";
 const llama_vocab*vocab=llama_model_get_vocab(g_model);std::vector<llama_token> tokens(full.size()+32);
 int n=llama_tokenize(vocab,full.c_str(),(int)full.size(),tokens.data(),(int)tokens.size(),true,true);
 if(n<0){tokens.resize((size_t)-n);n=llama_tokenize(vocab,full.c_str(),(int)full.size(),tokens.data(),(int)tokens.size(),true,true);}
 if(n<=0)return env->NewStringUTF("질문을 토큰화하지 못했어요.");tokens.resize(n);
 llama_memory_clear(llama_get_memory(g_ctx),true);llama_batch batch=llama_batch_get_one(tokens.data(),(int)tokens.size());
 if(llama_decode(g_ctx,batch)!=0)return env->NewStringUTF("프롬프트 처리에 실패했어요.");
 auto*sampler=llama_sampler_chain_init(llama_sampler_chain_default_params());llama_sampler_chain_add(sampler,llama_sampler_init_temp(0.65f));llama_sampler_chain_add(sampler,llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
 std::string out;const llama_token eos=llama_vocab_eos(vocab),eot=llama_vocab_eot(vocab);
 for(int i=0;i<384;i++){llama_token tok=llama_sampler_sample(sampler,g_ctx,-1);if(tok==eos||tok==eot)break;char piece[512];int len=llama_token_to_piece(vocab,tok,piece,sizeof(piece),0,false);if(len>0)out.append(piece,(size_t)len);llama_sampler_accept(sampler,tok);batch=llama_batch_get_one(&tok,1);if(llama_decode(g_ctx,batch)!=0)break;}
 llama_sampler_free(sampler);return env->NewStringUTF(out.c_str());
}
extern "C" JNIEXPORT void JNICALL Java_com_bulknote_app_MainActivity_00024Bridge_nativeUnload(JNIEnv*,jobject){std::lock_guard<std::mutex> lock(g_mutex);releaseAll();}
