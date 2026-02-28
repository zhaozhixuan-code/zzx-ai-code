// @ts-ignore
/* eslint-disable */
import request from '@/request'

/** 此处后端没有提供注释 GET /chatGroupMember/getInfo/${param0} */
export async function getInfo1(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: API.getInfo1Params,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params
  return request<API.ChatGroupMember>(`/chatGroupMember/getInfo/${param0}`, {
    method: 'GET',
    params: { ...queryParams },
    ...(options || {}),
  })
}

/** 此处后端没有提供注释 GET /chatGroupMember/list */
export async function list1(options?: { [key: string]: any }) {
  return request<API.ChatGroupMember[]>('/chatGroupMember/list', {
    method: 'GET',
    ...(options || {}),
  })
}

/** 此处后端没有提供注释 GET /chatGroupMember/page */
export async function page1(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: API.page1Params,
  options?: { [key: string]: any }
) {
  return request<API.PageChatGroupMember>('/chatGroupMember/page', {
    method: 'GET',
    params: {
      ...params,
      page: undefined,
      ...params['page'],
    },
    ...(options || {}),
  })
}

/** 此处后端没有提供注释 DELETE /chatGroupMember/remove/${param0} */
export async function remove1(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: API.remove1Params,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params
  return request<boolean>(`/chatGroupMember/remove/${param0}`, {
    method: 'DELETE',
    params: { ...queryParams },
    ...(options || {}),
  })
}

/** 此处后端没有提供注释 POST /chatGroupMember/save */
export async function save1(body: API.ChatGroupMember, options?: { [key: string]: any }) {
  return request<boolean>('/chatGroupMember/save', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 此处后端没有提供注释 PUT /chatGroupMember/update */
export async function update1(body: API.ChatGroupMember, options?: { [key: string]: any }) {
  return request<boolean>('/chatGroupMember/update', {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}
